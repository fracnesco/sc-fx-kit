// =====================================================================
// FxUnit - what an effect is, whichever graph it ends up in
//
// An effect here is two things that agree with each other: a piece of
// DSP written as a pseudo-UGen that reads named controls, and an object
// on the language side that holds the settings and pushes them at
// whatever node is running that DSP. The same DSP runs in three places,
// and the same object drives all three:
//
//   // in any graph of your own - an Ndef, a SynthDef - as a UGen
//   Ndef(\pad, { var sig = Saw.ar(110); ParaEQ.ar(sig, \eq) ! 2 });
//   e = ParaEQ(Ndef(\pad), \eq);       // drives that graph's \eq control
//   e.gui;
//
//   // on a bus, in place, with a synth of its own
//   e = ParaEQ.onBus(16, 2);
//   e.enabled = true;
//
//   // in a chain of effects on a bus - see FxChain
//   FxChain.master.units[0];
//
// Whatever way it runs, the object has the same settings, the same
// presets, the same clipboard, the same window - and a subclass writes
// its DSP once. What it provides:
//
//   *ar           the DSP, reading NamedControls off `name`
//   *defName      the name of the in place synth, per channel count
//   *synthDef     that synth: In - *ar - XOut
//   controls      the flat array the node is given
//   settings      what a preset and the clipboard hold
//   settings_     the same, put back
//   asDict / fromDict   what a set or a kit writes, enabled and all
//   *guiClass     its window
//   *panelClass   the same as a view, for a window with several
//
// `enabled` means what it says in every mode: with a node of its own the
// node is built or faded out; driving a graph, an `On` control is set
// and the DSP crossfades to dry. Off costs nothing when it can.
// =====================================================================

FxUnit {
	classvar defs, sent, quitHooks;
	classvar onBusUnits;			// [server, bus, class, controlName, target id, addAction] -> the unit there
	classvar mapTables;				// class -> its *mapParams, made once

	var <enabled = false;
	var <target;				// the node it drives, in driver mode - nil otherwise
	var <controlName;			// the control prefix on it
	var <synth;					// its own node, on a bus
	var <server, <bus, <numChannels;
	var placement;				// [target, addAction] for the node of its own
	var <chain;					// the FxChain it belongs to, or nil
	var <>analysisBus;			// where an analyser reads this effect, if not obvious
	var <>action;				// { |unit, what| } - called on every change
	var <label;					// what a window calls it
	var <guiObj;
	var <>onFree;
	var treeFunc, quitFunc;		// a node of its own comes back after cmd-period and a reboot
	var treeServer;				// the server those two were added for
	var busKey;					// its place in onBusUnits
	var warnedGone = false;		// a node that would not start says so once
	var freed = false, warnedFreed = false;	// freed or replaced: it does nothing more, and says so once

	*initClass {
		defs = IdentityDictionary.new;
		sent = IdentityDictionary.new;
		quitHooks = IdentityDictionary.new;
		onBusUnits = Dictionary.new;
		mapTables = IdentityDictionary.new;
	}

	// --------------------------------------------------------- what to build

	// the control the DSP reads when none is named
	*controlName { ^\fx }

	// the fade in and out of the chain when a node of its own is switched
	*fade { ^0.02 }

	// the DSP, the in place def, its name - a subclass has these
	*ar { |in, name| ^this.subclassResponsibility(thisMethod) }
	*defName { |numChannels| ^this.subclassResponsibility(thisMethod) }
	*synthDef { |numChannels| ^this.subclassResponsibility(thisMethod) }
	*guiClass { ^this.subclassResponsibility(thisMethod) }
	*panelClass { ^this.subclassResponsibility(thisMethod) }

	// the flat array of numbers the node is given, and what a preset holds
	controls { ^this.subclassResponsibility(thisMethod) }
	settings { ^this.subclassResponsibility(thisMethod) }
	settings_ { |data| ^this.subclassResponsibility(thisMethod) }

	// ------------------------------------------------ parameters, as a list

	// Every parameter something outside may move - a controller, a macro, the
	// knobs of a small card - the same way for every effect, so that none of
	// those has to know which effect it is talking to. One Event each:
	//
	//   (key:, label:, kind: \knob | \toggle | \menu, spec:, items:, section:,
	//    box:, hero:)
	//
	// spec is always there and always numbers: a toggle's is 0 or 1, a menu's
	// the index of its item. hero marks the few that say what the effect is
	// doing, the ones a card with room for three knobs would show. Made once
	// per class; a subclass answers *prMapParams.
	*mapParams {
		^mapTables[this] ?? {
			var list = this.prMapParams;
			mapTables[this] = list;		// put answers the dictionary, not the list
			list
		}
	}
	*prMapParams { ^[] }

	// What a small card shows (FxDeviceCard), when the hero knobs alone do not
	// make a usable one: an Array of keys, of [key, key] where one slot shows
	// whichever does something now, and of (key:, switch:, label:). nil for the
	// heroes as they come.
	*cardKeys { ^nil }

	// whether a parameter does anything with the others as they are: a panel
	// and a card dim it when not
	*isActive { |key, unit| ^true }
	*mapParam { |key| ^this.mapParams.detect { |e| e[\key] == key.asSymbol } }
	*mapKeys { ^this.mapParams.collect { |e| e[\key] } }

	// one of them, as a number in its spec; nil for a key it does not have
	mapGet { |key| ^nil }
	mapSet { |key, value| }

	// How late what comes out is, in seconds, against what went in: the delay
	// the effect cannot avoid (a lookahead, an fft window), never the delay it
	// is there to make (an echo, a pre-delay). 0 for most. Something that
	// runs effects side by side lines them up by this, and hears about a
	// change as changed(\latency).
	latency { ^0 }

	// ---------------------------------------------------------------- new

	// Drive a control on a node: a Synth, a Group, an Ndef or any other
	// NodeProxy - anything that answers to `set`. With no target the object
	// is a holder of settings that something else pushes; the compressor
	// keeps its sidechain eq that way.
	*new { |target, controlName|
		^super.new.init(target, controlName)
	}

	// A node of its own, in place on a bus. `target` and `addAction` say
	// where it goes in the tree; the tail of the default group by default.
	// Nothing is built until it is switched on. `controlName` only tells
	// two units of a class apart in one place (see prInitOnBus): the in
	// place def reads the class's control whatever it is.
	*onBus { |bus = 0, numChannels = 2, target, addAction = \addToTail, server, controlName|
		^this.new(nil).prInitOnBus(bus, numChannels, target, addAction, server, controlName)
	}

	init { |argTarget, argControlName|
		controlName = (argControlName ? this.class.controlName).asSymbol;
		label = this.class.name.asString;
		this.prInitSettings;
		this.target_(argTarget);
		^this
	}

	// a subclass sets its defaults here
	prInitSettings { }

	prInitOnBus { |argBus, argChannels, argTarget, addAction, argServer, argName|
		bus = FxUnit.prBusIndex(argBus) ? 0;
		numChannels = (argChannels ? 2).asInteger.clip(1, 64);
		server = argServer ?? { argTarget.tryPerform(\server) } ?? { Server.default };
		placement = [argTarget, addAction ? \addToTail];
		enabled = false;
		// one unit of a kind on a bus: evaluating the same line again
		// replaces the old one, as an Ndef or an FxChain of a name does,
		// rather than leaving it to come back after every cmd-period.
		// Another control name, target or add action is another place
		busKey = this.class.prBusKey(server, bus, (argName ? controlName).asSymbol, argTarget, addAction);
		onBusUnits[busKey] !? { |old| if (old !== this) { old.free } };
		onBusUnits[busKey] = this;
		this.prWatchTree;
		^this
	}

	free {
		if (freed) { ^this };
		freed = true;
		this.prUnwatchTree;
		this.prForgetBusKey;
		this.prFreeNode;
		guiObj !? (_.close);
		chain !? (_.remove(this));
		onFree.value(this);
		this.changed(\freed);
	}

	label_ { |str| label = str.asString; this.changed(\label) }

	// ------------------------------------------------------------- target

	// The node this drives. Setting one pushes the settings at it - unless
	// the node already carries some, as an Ndef does in its nodeMap once it
	// has been set, in which case those are taken over.
	target_ { |node|
		var map, held;
		if (this.prIsFreed) { ^this };
		target = node;
		if (node.isNil) { ^this };
		server = node.tryPerform(\server) ? Server.default;
		map = node.tryPerform(\nodeMap);
		held = map !? { |m| m.tryPerform(\at, controlName) };
		if (held.notNil and: { this.prAcceptControls(held) }) {
			held = map.tryPerform(\at, this.onName);
			enabled = held.isNil or: { held.asArray.first > 0 };
			this.changed(\settings);
			this.changed(\enabled);
		} {
			enabled = true;
			this.prPushAll;
		};
		^this
	}

	// what a node carried, read back into settings: answers whether it could
	prAcceptControls { |array| ^false }

	isDriving { ^target.notNil }
	isOnBus { ^placement.notNil or: { chain.notNil } }

	onName { ^(controlName.asString ++ "On").asSymbol }

	// ------------------------------------------------------------ enabled

	enabled_ { |bool|
		bool = bool.booleanValue;
		if (this.prIsFreed) { ^this };
		if (bool == enabled) { ^this };
		enabled = bool;
		this.prEnabledChanged;
		this.prChanged(\enabled);
	}

	isActive { ^enabled }

	// A freed unit - by hand, or replaced by a newer one on its bus - makes
	// no node again: one it made now would be nobody's, back after every
	// cmd-period. It says so the first time it is asked
	prIsFreed {
		if (freed.not) { ^false };
		if (warnedFreed.not) {
			warnedFreed = true;
			("%: it was freed (or replaced by another on its bus) - "
				++ "make a new one").format(label).warn;
		};
		^true
	}

	isFreed { ^freed }

	prEnabledChanged {
		case
		{ chain.notNil } { chain.prSyncUnit(this); chain.prUnitSwitched(this) }
		{ placement.notNil } { this.prSyncNode }
		{ true } { this.prPushOn };
	}

	// ------------------------------------------------------------- pushing

	// everything the node has to hear: the settings, and whether it is on
	prPushAll {
		this.prPush;
		this.prPushOn;
	}

	prPush {
		var c = this.controls;
		synth !? { |sy| sy.set(controlName, c) };
		target !? { |t| t.set(controlName, c) };
	}

	prPushOn {
		target !? { |t| t.set(this.onName, enabled.binaryValue) };
	}

	prChanged { |what|
		action.value(this, what);
		this.changed(what);
	}

	// ------------------------------------------------------------ the node

	// The node of its own: made when it is enabled and there is a server
	// to make it on, faded out when it is not. The def goes to the server
	// first and this comes back once it has arrived.
	prSyncNode {
		var tgt, act;
		if (this.prIsFreed) { ^this };
		if (chain.notNil) { ^chain.prSyncUnit(this) };
		if (placement.isNil or: { server.isNil }) { ^this };
		if (enabled.not) { ^this.prFreeNode };
		if (synth.notNil) { ^this };
		if (server.serverRunning.not) { ^this };
		// queued: quiet if the unit was freed meanwhile
		if (this.class.prEnsureDef(server, numChannels, { if (freed.not) { this.prSyncNode } }).not) { ^this };
		tgt = placement[0] ?? { server.defaultGroup };
		act = placement[1] ? \addToTail;
		this.prMakeNode(tgt, act);
		this.prWatchNode(synth);
	}

	// A node of its own that ends (freed by hand, its group freed) or never
	// starts (its target gone) leaves `synth` nil, so the next enabled_ or
	// tree makes it again. Whether it started is known after a sync: the
	// server says /n_go before it answers /synced.
	prWatchNode { |sy|
		if (sy.isNil) { ^this };
		// not notified: no /n_go and no /n_end either, so nothing to watch
		if (server.tryPerform(\notified) == false) { ^this };
		sy.onFree { if (synth === sy) { synth = nil; this.changed(\node) } };
		{
			server.sync;
			if (synth === sy and: { sy.isPlaying.not }) {
				synth = nil;
				sy.releaseDependants;
				NodeWatcher.unregister(sy);
				this.changed(\node);
				if (warnedGone.not) {
					warnedGone = true;
					("%: its node did not start (where it was to go is gone) - "
						++ "switch it off and on to try again").format(label).warn;
				};
			} {
				if (synth === sy) { warnedGone = false };
			};
		}.fork(AppClock);
	}

	// One bundle, sent now rather than at the server's latency: an array
	// control cannot travel in s_new, so the settings follow in the message
	// after it, and everything told to this node from here on is sent
	// without latency too and stays behind them.
	prMakeNode { |tgt, addAction|
		server.makeBundle(nil, {
			synth = Synth(this.class.defName(numChannels),
				[\bus, bus, \fade, this.class.fade] ++ this.prNodeArgs, tgt, addAction);
			this.prPush;
		});
		this.changed(\node);
		^synth
	}

	// a subclass with more to say to its node adds it here
	prNodeArgs { ^[] }

	prFreeNode {
		synth !? { |sy| sy.set(\fade, this.class.fade, \gate, 0) };
		synth = nil;
		this.changed(\node);
	}

	// the server took it with everything else
	prNodeGone { synth = nil }

	// A node of its own outside a chain: the tree went (cmd-period, a
	// reboot), so `synth` points at nothing and is made again. A chain
	// does this for its own units, so joining one stops it.
	// A group of someone else's as target may only be made again by a
	// later tree function, which may itself wait a sync before making it:
	// the node waits two, so that group's message has gone first. The
	// default group is there before any tree function runs.
	prWatchTree {
		this.prUnwatchTree;
		treeServer = server;
		treeFunc = {
			this.prNodeGone;
			if (this.prTargetIsDefault) { this.prSyncNode } {
				{ server.sync; server.sync; if (freed.not) { this.prSyncNode } }.fork(AppClock)
			};
		};
		quitFunc = { this.prNodeGone };
		ServerTree.add(treeFunc, treeServer);
		ServerQuit.add(quitFunc, treeServer);
	}

	prTargetIsDefault {
		var t = placement !? { placement[0] };
		if (t.isNil or: { t.isKindOf(Server) }) { ^true };
		t = t.asTarget;
		^t.isKindOf(RootNode) or: { t.nodeID == server.defaultGroup.nodeID }
	}

	// from the server they were added for: target_ may have changed `server`
	prUnwatchTree {
		treeFunc !? { ServerTree.remove(treeFunc, treeServer) };
		quitFunc !? { ServerQuit.remove(quitFunc, treeServer) };
		treeFunc = nil;
		quitFunc = nil;
		treeServer = nil;
	}

	prForgetBusKey {
		busKey !? { if (onBusUnits[busKey] === this) { onBusUnits.removeAt(busKey) } };
		busKey = nil;
	}

	// the unit on that bus and place, of that class and control name, if there is one
	*onBusUnit { |bus = 0, server, controlName, target, addAction = \addToTail|
		var srv = server ?? { target.tryPerform(\server) } ?? { Server.default };
		^onBusUnits[this.prBusKey(srv, FxUnit.prBusIndex(bus) ? 0,
			(controlName ? this.controlName).asSymbol, target, addAction)]
	}

	// The target by node id, so a new object for the same group is the same
	// place, and the default group the same as none
	*prBusKey { |srv, busIndex, ctlName, tgt, addAction|
		var id = tgt !? { tgt.tryPerform(\asTarget) !? { |n| n.tryPerform(\nodeID) } };
		if (id.notNil and: { id == srv.defaultGroup.nodeID }) { id = nil };
		^[srv, busIndex, this, ctlName, id, (addAction ? \addToTail).asSymbol]
	}

	// ------------------------------------------------------------- chains

	prJoinChain { |aChain|
		// a node of its own on a bus goes: the chain makes the next one
		this.prUnwatchTree;
		this.prForgetBusKey;
		if (chain.isNil) { synth !? { this.prFreeNode } };
		chain = aChain;
		server = aChain.server;
		bus = aChain.bus;
		numChannels = aChain.numChannels;
		placement = nil;
		target = nil;
	}

	// the chain it is in moved to another bus or server
	prChainMoved { |aChain|
		server = aChain.server;
		bus = aChain.bus;
		numChannels = aChain.numChannels;
	}

	prLeaveChain {
		this.prFreeNode;
		chain = nil;
	}

	// ----------------------------------------------------------- analysis

	// Where an analyser reading this effect's output goes in the tree, as
	// [target, addAction] - after the node of its own, at the tail of a
	// driven proxy's group - and which bus it reads there. nil where there
	// is nothing to say: a driven Synth does not know its bus, so
	// `analysisBus` has to be set by hand before a spectrum can be drawn.
	analysisPlacement {
		var g;
		if (synth.notNil) { ^[synth, \addAfter] };
		if (target.notNil) {
			g = target.tryPerform(\group);
			g !? { ^[g, \addToTail] };
		};
		^nil
	}

	analysisBusIndex {
		^analysisBus ?? {
			if (target.notNil) {
				target.tryPerform(\bus) !? { |b| b.tryPerform(\index) }
			} { bus }
		}
	}

	analysisChannels {
		^numChannels ?? { target.tryPerform(\numChannels) } ? 2
	}

	analysisAvailable {
		^this.analysisPlacement.notNil and: { this.analysisBusIndex.notNil }
	}

	// ----------------------------------------------------------------- gui

	gui { |x, y|
		if (guiObj.isNil) { guiObj = this.class.guiClass.new(this, x, y) } { guiObj.front };
		^guiObj
	}

	prGuiClosed { guiObj = nil }

	// the same controls as a view, for a window that shows several effects
	panel { |parent| ^this.class.panelClass.new(this, parent) }

	// --------------------------------------------------------------- state

	asDict { ^(enabled: enabled, settings: this.settings) }

	fromDict { |d|
		if (d.isNil or: { this.prIsFreed }) { ^this };
		d[\settings] !? { |v| this.settings_(v) };
		d[\enabled] !? { |v| this.enabled_(v) };
	}

	// --------------------------------------------------------------- defs

	// The in place def for a channel count, built once and kept.
	*def { |numChannels|
		var key = this.defName(numChannels);
		^defs[key] ?? { defs[key] = this.synthDef(numChannels); defs[key] }
	}

	// Whether the def is on that server. If not, it is sent, and `done` is
	// called once the server has it - so a node is never asked for before
	// its def has arrived.
	*prEnsureDef { |server, numChannels, done|
		var key = this.defName(numChannels);
		var servers = sent[key] ?? { sent[key] = IdentitySet.new; sent[key] };
		if (servers.includes(server)) { ^true };
		this.prWatchQuit(server);
		{
			var d = this.def(numChannels);
			d.add;
			// add has told every booted server already. A big def goes by a file
			// that each send deletes, so telling one twice would lose it
			if (Server.allBootedServers.includes(server).not) { d.send(server) };
			server.sync;
			servers.add(server);
			done.value;
		}.fork(AppClock);
		^false
	}

	// after a quit nothing that was sent is there any more
	*prWatchQuit { |server|
		quitHooks[server] ?? {
			var f = { sent.do { |set| set.remove(server) } };
			quitHooks[server] = f;
			ServerQuit.add(f, server);
		};
	}

	*prDefGone { |server| sent.do { |set| set.remove(server) } }

	// ------------------------------------------------------------ helpers

	// A Bus, a bus index, a BusManager name - all ways of saying which bus.
	// The registry is looked up by name at runtime, so this compiles and
	// runs without it; without it a name is not a bus and says so.
	*prBusIndex { |val|
		var reg, index;
		if (val.isNil) { ^nil };
		if (val.isNumber) { ^val.asInteger };
		if (val.isKindOf(Bus)) { ^val.index };
		if (val.isKindOf(Symbol) or: { val.isKindOf(String) }) {
			reg = \BusManager.asClass;
			index = reg !? { reg.index(val.asSymbol) };
			if (index.isNumber) { ^index.asInteger };
			("% is not a registered bus name%".format(val.asCompileString,
				if (reg.isNil) { " - BusManager is not installed" } { "" })).warn;
			^nil
		};
		if (val.respondsTo(\tapBus)) { ^this.prBusIndex(val.tapBus) };
		index = val.tryPerform(\asControlInput);
		if (index.isNumber) { ^index.asInteger };
		("% cannot be read as a bus".format(val.asCompileString)).warn;
		^nil
	}

	printOn { |stream|
		stream << this.class.name << "(" << label << ", "
			<< if (enabled) { "on" } { "off" } << ")"
	}
}
