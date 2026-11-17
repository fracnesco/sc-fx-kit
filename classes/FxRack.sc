// =====================================================================
// FxRack - chains of effects side by side, lined up in time
//
// A rack is an effect like any other - it goes in a chain, has a card, is
// saved with the chain - whose inside is several chains in parallel: what
// comes in goes to every one of them, what comes out of them is added up.
//
//   r = FxChain.master.add(FxRack());      // one chain to begin with: nothing changes
//   r.branches[0].chain.add(Compressor()); // an effect in the first chain
//   b = r.addBranch("dry");                // a second chain, empty: the dry sound
//   b.level = -6;                          // in dB
//   r.enabled = true;
//
// Effects that cannot help being late (a compressor's lookahead, a limiter,
// an fft) say by how much - FxUnit's `latency` - and the rack holds every
// other chain back by the difference, so that what is added up is in time
// and a dry chain beside a late one does not comb. A chain's `align` takes it
// out of that (it is not held back and nothing waits for it); the rack's
// `live` holds nothing back at all. When a latency changes - a knob, an
// effect switched - the rack waits for it to settle and crossfades to the new
// delay. The rack is itself as late as its latest aligned chain, and says so,
// so a rack inside a chain of another rack is lined up too. One level of
// that: a rack in a rack, no deeper.
//
// On the server it is a group where an effect has a synth: a copy of the bus
// to each chain's own bus, the chains, each chain's delay and level onto a
// sum, and the sum back on the bus (faded, as every effect is).
// =====================================================================

FxRackBranch {
	var <rack, <chain, <name, <level = 0, <mute = false, <solo = false, <align = true;
	var <>bus, <>inSynth, <>outSynth, <delay = 0, tap = 0, serial = 0, ctrl;

	*new { |rack, name| ^super.new.init(rack, name) }

	init { |argRack, argName|
		rack = argRack;
		name = (argName ? "chain").asString;
		chain = FxChain.owned(name.asSymbol, rack.numChannels ? 2, rack.server);
		chain.owner = rack;
		chain.onChange = { rack.prSettle };
		ctrl = SimpleController(chain).put(\units, { rack.prWatchUnits; rack.prSettle; rack.changed(\rack) });
	}

	name_ { |str| name = str.asString; rack.prChanged(\rack) }

	// in dB, -60 (nothing) to +6
	level_ { |db| level = db.clip(-60, 6); this.prPushAmp; rack.prChanged(\rack) }
	mute_ { |bool| mute = bool == true; rack.prPushAmps; rack.prChanged(\rack) }
	solo_ { |bool| solo = bool == true; rack.prPushAmps; rack.prChanged(\rack) }
	align_ { |bool| align = bool == true; rack.prAlign; rack.prChanged(\rack) }

	index { ^rack.branches.indexOf(this) }

	// how late this chain is by itself: what is on in it, added up
	latency {
		if (chain.enabled.not) { ^0 };
		^chain.units.select { |u| u.enabled }.collect { |u| u.latency ? 0 }.sum
	}

	// how long it is held back so the others catch up, in seconds
	heldBack { ^delay / (rack.server !? { |s| s.sampleRate } ? 48000) }

	amp {
		if (mute or: { rack.anySolo and: { solo.not } }) { ^0 };
		^if (level <= -60) { 0 } { level.dbamp }
	}

	// what the chain is made of, for a label: "growl → eq", or "-"
	summary {
		^if (chain.units.isEmpty) { "-" } {
			chain.units.collect { |u| (u.class.tryPerform(\title) ? u.class.name).asString }.join(" → ")
		}
	}

	prPushAmp { outSynth !? { |sy| sy.set(\amp, this.amp) } }

	// A new delay, in samples, without a click: the tap that is not heard is
	// put there, then the two are crossfaded, then the old one follows so the
	// pair is the same again. A newer change overtakes an older one.
	prDelay { |samples|
		var old = tap, mine;
		samples = samples.round.asInteger.max(0);
		if (samples == delay) { ^this };
		delay = samples;
		if (outSynth.isNil) { ^this };
		serial = serial + 1;
		mine = serial;
		tap = 1 - tap;
		outSynth.set(#[\delayA, \delayB][tap], samples);
		SystemClock.sched(0.02, {
			if (serial == mine) { outSynth !? { |sy| sy.set(\which, tap) } };
			nil
		});
		SystemClock.sched(0.02 + FxRack.crossfade + 0.05, {
			if (serial == mine) { outSynth !? { |sy| sy.set(#[\delayA, \delayB][old], samples) } };
			nil
		});
	}

	prOutArgs { ^[\delayA, delay, \delayB, delay, \which, tap, \amp, this.amp] }

	prNodesGone { inSynth = nil; outSynth = nil; chain.nodesGone }

	asDict {
		^(name: name, level: level, mute: mute, solo: solo, align: align, units: chain.asDict[\units])
	}

	fromDict { |d|
		d[\name] !? { |v| name = v.asString };
		d[\level] !? { |v| level = v.clip(-60, 6) };
		d[\mute] !? { |v| mute = v == true };
		d[\solo] !? { |v| solo = v == true };
		d[\align] !? { |v| align = v == true };
		chain.setUnits(d[\units] ? []);
	}

	prFree {
		ctrl.remove;
		chain.onChange = nil;
		chain.free;
	}

	printOn { |stream| stream << "FxRackBranch(" << name << ", " << chain.units.size << " effects)" }
}


FxRack : FxUnit {
	classvar <>crossfade = 0.03;		// seconds a change of delay takes
	classvar <>settle = 0.25;			// seconds a latency has to stand still before the delays follow
	classvar <delayFrames = 32768;		// each channel's delay line: 0.68 s at 48 kHz

	var <branches, <live = false;
	var <>shown = 0;					// the chain a strip shows the effects of
	var sumBus, mixSynth, dying, busQuitFunc, busServer;
	var watched, settleAt, settling = false, lastLatency = 0, warnedLong = false;

	*title { ^"rack" }
	*insertCategory { ^\utility }
	*identityColour { ^Color.grey(0.8) }
	*windowSize { ^Point(1040, 520) }
	*guiClass { ^FxDeviceGUI }
	*panelClass { ^FxRackPanel }

	// the sum back onto the bus: the def a chain asks for by name. The two
	// others go with it.
	*defName { |numChannels| ^("fxRackMix_" ++ numChannels).asSymbol }
	*inDefName { |numChannels| ^("fxRackIn_" ++ numChannels).asSymbol }
	*outDefName { |numChannels| ^("fxRackOut_" ++ numChannels).asSymbol }

	*synthDef { |numChannels|
		var n = numChannels.asInteger;
		^SynthDef(this.defName(n), { |bus = 0, in = 0, gate = 1, fade = 0.02|
			var env = EnvGen.kr(Env.asr(fade, 1, fade), gate, doneAction: Done.freeSelf);
			XOut.ar(bus, env, In.ar(in, n));
		})
	}

	*prHelperDefs { |numChannels|
		var n = numChannels.asInteger;
		^[
			SynthDef(this.inDefName(n), { |bus = 0, out = 0|
				Out.ar(out, In.ar(bus, n));
			}),
			// DelTapRd without interpolation is exact to the sample, 0 included
			// (measured; DelayN is a sample out here and there)
			SynthDef(this.outDefName(n), { |in = 0, out = 0, delayA = 0, delayB = 0, which = 0, amp = 1, xfade = 0.03|
				var x = VarLag.kr(which, xfade);
				var sig = In.ar(in, n).asArray.collect { |ch|
					var buf = LocalBuf(FxRack.delayFrames, 1).clear;
					var phase = DelTapWr.ar(buf, ch);
					var a = DelTapRd.ar(buf, phase, delayA * SampleDur.ir, 1);
					var b = DelTapRd.ar(buf, phase, delayB * SampleDur.ir, 1);
					(a * (1 - x)) + (b * x)
				};
				Out.ar(out, sig * Lag.kr(amp, 0.02));
			})
		]
	}

	// the three of them: the chain asks for one, and waits for the server
	*def { |numChannels|
		this.prHelperDefs(numChannels).do(_.add);
		^super.def(numChannels)
	}

	// a rack has no parameters of a fixed kind: its controls are its chains'
	controls { ^[] }
	prPush { }
	prPushOn { }

	prInitSettings {
		branches = [];
		watched = IdentitySet.new;
		this.addBranch("a");
	}

	prInitOnBus { |argBus, argChannels, argTarget, addAction, argServer, argName|
		super.prInitOnBus(argBus, argChannels, argTarget, addAction, argServer, argName);
		this.prBranchesFollow;
	}

	// ------------------------------------------------------------- chains

	// Another chain beside the others, empty: it passes what comes in, so it
	// adds that much level until something is put in it or its level is taken down.
	addBranch { |name|
		var b = FxRackBranch(this, name ?? { this.prFreeName });
		branches = branches.add(b);
		if (synth.notNil) { this.prMakeBranchNodes(b) };
		this.prAlign;
		this.prPushAmps;
		this.prChanged(\branches);
		^b
	}

	removeBranch { |which|
		var b = if (which.isKindOf(FxRackBranch)) { which } { branches[which.asInteger] };
		if (b.isNil or: { branches.includes(b).not }) { ^this };
		if (branches.size == 1) { "FxRack: a rack keeps one chain - take the rack out instead.".warn; ^this };
		branches = branches.copy;
		branches.remove(b);
		shown = shown.clip(0, branches.size - 1);
		this.prFreeBranchNodes(b);
		b.prFree;
		this.prWatchUnits;
		this.prAlign;
		this.prPushAmps;
		this.prChanged(\branches);
	}

	branchAt { |which|
		^if (which.isNumber) { branches[which.asInteger] } {
			branches.detect { |b| b.name == which.asString } }
	}

	numBranches { ^branches.size }
	anySolo { ^branches.any { |b| b.solo } }

	prFreeName {
		var taken = branches.collect { |b| b.name };
		^"abcdefghijklmnopqrstuvwxyz".as(Array).collect(_.asString).detect { |n| taken.includes(n).not } ? "chain"
	}

	// how deep it sits: 0 in a chain of its own, 1 in a chain of another rack
	depth { ^chain !? { |c| c.owner !? { |r| r.depth + 1 } } ? 0 }

	// FxChain asks before it lets a rack in: one level of racks in racks, and
	// never a rack inside itself
	prMayJoin { |aChain|
		var owner = aChain.owner, up;
		if (owner.isNil) { ^true };
		up = owner;
		while { up.notNil } {
			if (up === this) { ^false };
			up = up.chain !? { |c| c.owner };
		};
		// its own racks would then be too deep as well
		if (branches.any { |b| b.chain.units.any { |u| u.isKindOf(FxRack) } }) { ^false };
		^owner.depth < 1
	}

	// ------------------------------------------------------------ in time

	// holds nothing back: every chain as early as it can be, out of line
	live_ { |bool|
		live = bool == true;
		this.prAlign;
		this.prChanged(\rack);
	}

	// as late as the latest chain that is lined up
	latency {
		^branches.select { |b| b.align }.collect { |b| b.latency }.maxItem ? 0
	}

	// the delays as they should be now, and word of it if the rack's own changed
	prAlign {
		var sr = server !? { |s| s.sampleRate } ? 48000;
		var max = this.latency, most = delayFrames - 128, now;
		branches.do { |b|
			var want = if (live or: { b.align.not }) { 0 } { ((max - b.latency) * sr).round.asInteger };
			if (want > most) {
				if (warnedLong.not) {
					warnedLong = true;
					"FxRack: a chain would have to wait % ms, more than the % ms a rack can hold - it stays out of line."
						.format((want / sr * 1000).round, (most / sr * 1000).round).warn;
				};
				want = most;
			};
			b.prDelay(want);
		};
		now = this.latency;
		if (now != lastLatency) { lastLatency = now; this.changed(\latency) };
		this.changed(\rack);
	}

	// something inside changed what it costs: once it has stood still
	prSettle {
		settleAt = Main.elapsedTime + settle;
		if (settling) { ^this };
		settling = true;
		SystemClock.sched(settle, {
			var wait = settleAt - Main.elapsedTime;
			if (wait > 0.01) { wait } {
				settling = false;
				if (this.isFreed.not) { this.prAlign };
				nil
			}
		});
	}

	// every effect in every chain tells the rack when its latency changes
	prWatchUnits {
		var now = IdentitySet.new;
		branches.do { |b| b.chain.units.do { |u| now.add(u) } };
		watched.do { |u| if (now.includes(u).not) { u.removeDependant(this) } };
		now.do { |u| if (watched.includes(u).not) { u.addDependant(this) } };
		watched = now;
	}

	update { |obj, what|
		if (#[\latency, \enabled].includes(what)) { this.prSettle };
	}

	prPushAmps { branches.do(_.prPushAmp) }

	// ----------------------------------------------------------- the nodes

	prAllocBuses {
		if (busServer.notNil and: { busServer !== server }) { this.prFreeBuses };
		if (busQuitFunc.isNil) {
			// a quit resets the allocator: the numbers are nobody's, not ours to give back
			busServer = server;
			busQuitFunc = { sumBus = nil; branches.do { |b| b.bus = nil } };
			ServerQuit.add(busQuitFunc, busServer);
		};
		sumBus = sumBus ?? { Bus.audio(server, numChannels) };
		branches.do { |b| b.bus = b.bus ?? { Bus.audio(server, numChannels) } };
	}

	prFreeBuses {
		busQuitFunc !? { ServerQuit.remove(busQuitFunc, busServer) };
		busQuitFunc = nil;
		busServer = nil;
		sumBus !? (_.free);
		sumBus = nil;
		branches.do { |b| b.bus !? (_.free); b.bus = nil };
	}

	// A group where an effect has a synth. In it, in this order: every chain's
	// copy of the bus, then each chain and after it its delay and level, then
	// the sum back onto the bus.
	prMakeNode { |tgt, addAction|
		dying !? { |g| g.free };
		dying = nil;
		this.prAllocBuses;
		synth = Group(tgt, addAction);
		mixSynth = Synth(this.class.defName(numChannels),
			[\bus, bus, \in, sumBus, \fade, this.class.fade], synth, \addToTail);
		this.prAlignQuietly;
		branches.do { |b| this.prMakeBranchNodes(b) };
		this.changed(\node);
		^synth
	}

	// the delays the nodes start with: no crossfade for a node that is not there yet
	prAlignQuietly {
		branches.do { |b| b.outSynth = nil };
		this.prAlign;
	}

	prMakeBranchNodes { |b|
		if (synth.isNil or: { mixSynth.isNil }) { ^this };
		b.bus = b.bus ?? { Bus.audio(server, numChannels) };
		b.inSynth = Synth(this.class.inDefName(numChannels), [\bus, bus, \out, b.bus], synth, \addToHead);
		b.chain.rebuild(mixSynth, \addBefore, b.bus);
		b.outSynth = Synth(this.class.outDefName(numChannels),
			[\in, b.bus, \out, sumBus, \xfade, crossfade] ++ b.prOutArgs, mixSynth, \addBefore);
	}

	prFreeBranchNodes { |b|
		var in = b.inSynth, out = b.outSynth, bus = b.bus;
		b.inSynth = nil; b.outSynth = nil; b.bus = nil;
		b.chain.freeNodes;
		out !? { out.set(\amp, 0) };
		SystemClock.sched(0.1, {
			if (server.serverRunning) { in !? (_.free); out !? (_.free) };
			if (busQuitFunc.notNil) { bus !? (_.free) };
			nil
		});
	}

	// the sum fades out, then the whole group goes
	prFreeNode {
		var old = synth, mix = mixSynth;
		if (old.isNil) { ^this };
		synth = nil;
		mixSynth = nil;
		branches.do(_.prNodesGone);
		dying = old;
		mix.set(\fade, this.class.fade, \gate, 0);
		SystemClock.sched(this.class.fade + 0.05, {
			if (dying === old) {
				dying = nil;
				// quietly: it may have gone already, with the chain it was in
				if (server.serverRunning) {
					server.sendBundle(nil, ['/error', -1], ['/n_free', old.nodeID]);
				};
				if (synth.isNil and: { chain.isNil } and: { this.isOnBus.not }) { this.prFreeBuses };
			};
			nil
		});
		this.changed(\node);
	}

	prNodeGone {
		synth = nil;
		mixSynth = nil;
		dying = nil;
		branches.do(_.prNodesGone);
	}

	// ------------------------------------------------------------- chains

	prBranchesFollow {
		branches.do { |b|
			b.chain.server_(server);
			b.chain.numChannels_(numChannels ? 2);
		};
	}

	prJoinChain { |aChain|
		if (busServer.notNil and: { busServer !== aChain.server }) { this.prFreeBuses };
		if (numChannels.notNil and: { numChannels != aChain.numChannels }) { this.prFreeBuses };
		super.prJoinChain(aChain);
		this.prBranchesFollow;
	}

	// the chain it is in moved to another bus: the two ends follow
	prChainMoved { |aChain|
		super.prChainMoved(aChain);
		this.prBranchesFollow;
		branches.do { |b| b.inSynth !? { |sy| sy.set(\bus, bus) } };
		mixSynth !? { |sy| sy.set(\bus, bus) };
	}

	free {
		if (this.isFreed) { ^this };
		super.free;
		watched.do { |u| u.removeDependant(this) };
		watched = IdentitySet.new;
		branches.do(_.prFree);
		SystemClock.sched(this.class.fade + 0.1, { this.prFreeBuses; nil });
	}

	// --------------------------------------------------------------- state

	settings {
		^(live: live, shown: shown, branches: branches.collect(_.asDict))
	}

	// the rack made to be exactly these chains, in this order
	settings_ { |data|
		var entries = (data[\branches] ? []).asArray;
		if (entries.isEmpty) { entries = [(name: "a")] };
		live = data[\live] == true;
		while { branches.size > entries.size } {
			var b = branches.last;
			branches = branches.drop(-1);
			this.prFreeBranchNodes(b);
			b.prFree;
		};
		while { branches.size < entries.size } {
			var b = FxRackBranch(this, entries[branches.size][\name]);
			branches = branches.add(b);
			if (synth.notNil) { this.prMakeBranchNodes(b) };
		};
		branches.do { |b, i| b.fromDict(entries[i]) };
		shown = (data[\shown] ? 0).asInteger.clip(0, branches.size - 1);
		this.prWatchUnits;
		this.prAlign;
		this.prPushAmps;
		this.prChanged(\branches);
		this.prChanged(\settings);
	}

	printOn { |stream|
		stream << "FxRack(" << branches.size << " chains, "
			<< if (enabled) { "on" } { "off" } << ")"
	}
}
