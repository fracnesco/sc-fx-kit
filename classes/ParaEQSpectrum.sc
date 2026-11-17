// =====================================================================
// ParaEQSpectrum - what an eq is working on, drawn behind its curve
//
// The eq window draws the curve the filters make. This is the other
// half of that picture: the sound those filters are working on,
// analysed while it plays and drawn underneath the curve, so a cut can
// be put where something is rather than where it might be.
//
//   spectrum = ParaEQSpectrum(eq);
//   spectrum.action = { { eqView.refresh }.defer };
//   spectrum.running = true;      // while the eq page is up
//   ...
//   spectrum.free;                // in window.onClose
//
// It costs nothing until it is asked for: no analyser exists unless a
// window is showing an eq that can be analysed, and
// `ParaEQSpectrum.on = false` turns it off everywhere.
//
// Where it reads is the eq's business - see FxUnit:analysisPlacement.
// An eq with a node of its own is read just after that node; an eq
// driving an Ndef is read at the tail of the Ndef's group, on the
// Ndef's bus; an eq driving a plain Synth has no bus to read until its
// `analysisBus` is set. An eq that is switched off has no picture.
//
// How it works, and why it works that way:
//
//   - one FFT on the bus, after the eq, so what is drawn is what leaves
//     it. The synth is the FFT and nothing else: it fills a buffer and
//     never reads it back;
//   - the language reads that buffer, a frame at a time, and takes the
//     loudest bin in each of its bands. That is the whole point of
//     reading it here rather than reducing it on the server: a band
//     near the top of the range covers a hundred bins, and a tone in it
//     is in one of them. Sampling a few would miss it, and did.
//
// So the picture is a peak reading, log spaced, of everything going
// through - not a measuring instrument, but honest about where the
// energy is, which is what an eq is aimed by.
// =====================================================================

ParaEQSpectrum {
	// off here and no window shows one - nothing is analysed either
	classvar <>on = true;

	classvar <>fftSize = 4096;			// 93 ms at 44.1k: bins 10.8 Hz apart,
										// which is what the bottom octave needs
	classvar <>numBands = 64;			// what is drawn, log spaced
	classvar <>pollRate = 15;			// frames a second read from the server
	classvar <>syncRate = 2;			// how often the node is checked

	// The scale the trace is drawn on, in dB, and how fast it falls back.
	classvar <>floorDb = -78, <>topDb = 6;
	classvar <>fallDb = 40, <>peakFallDb = 9;
	classvar <>dbTrim = 0;				// nudge the whole scale, if it reads low

	classvar <>minFreq = 20, <>maxFreq = 20000;

	classvar bandFreqs;					// worked out once, from the above
	classvar defs, defsReady, defsPending;

	var <eq, <>action;
	var <levels, <peaks;				// dB per band - what the view draws
	var <freqs;							// and the frequency each one sits at
	var buffer, synth, responder, task;
	var running = false, serverWasUp = false, bufPending = false;
	var trackedTarget, trackedBus, trackedChannels, lastFrame = 0, madeAt = 0;
	var frame, edges, fetchCount, fetching = false, chunksLeft = 0, fetchStart = 0;

	*initClass {
		defs = IdentityDictionary.new;
		defsReady = IdentityDictionary.new;
		defsPending = IdentityDictionary.new;
	}

	*new { |eq| ^super.new.init(eq) }

	init { |argEq|
		eq = argEq;
		freqs = ParaEQSpectrum.freqs;
		this.prClear;
		^this
	}

	// ------------------------------------------------------------ the bands

	// The centre of each band, log spaced. The eq draws its frequencies
	// that way too, so the two line up without either knowing about it.
	*freqs {
		^bandFreqs ?? {
			bandFreqs = Array.fill(numBands, { |i|
				minFreq * ((maxFreq / minFreq) ** (i / (numBands - 1).max(1)))
			})
		}
	}

	// Which bins of an FFT frame each band covers, at this sample rate:
	// [first, last] per band, never empty and never past the end. Worked
	// out when the analyser starts, since a bin number depends on the rate
	// the server turned out to be running at.
	*bandBins { |sr|
		var f = this.freqs;
		var ratio = (maxFreq / minFreq) ** (1 / (numBands - 1).max(1));
		var edge = ratio.sqrt;
		var top = ((fftSize * 0.5) - 1).asInteger;
		var scale = fftSize / sr.clip(8000, 192000);
		^f.collect { |mid|
			var lo = ((mid / edge) * scale).floor.asInteger.clip(1, top);
			var hi = ((mid * edge) * scale).ceil.asInteger.clip(lo, top);
			[lo, hi]
		}
	}

	// How much of the frame is worth reading: everything up to the top of
	// the drawn range, and not the rest of it.
	*fetchCount { |sr|
		^((this.bandBins(sr).last[1] + 1) * 2).clip(4, fftSize)
	}

	// Everything worked out from the settings above, forgotten, so that
	// changing one of them takes. The defs go with them.
	*rebuild {
		bandFreqs = nil;
		defs = IdentityDictionary.new;
		defsReady = IdentityDictionary.new;
		defsPending = IdentityDictionary.new;
	}

	// ------------------------------------------------------------- the def

	*defName { |numChannels| ^("paraEQSpectrum" ++ numChannels.asInteger).asSymbol }

	// One FFT and no more: it fills the buffer, the language reads it.
	// Nothing is done to the bus, so nothing has to be undone, and the
	// synth writes to no bus at all. Built per channel count, since the
	// channels are summed to one before the FFT.
	*synthDef { |numChannels|
		var n = numChannels.asInteger.max(1);
		var key = this.defName(n);
		^defs[key] ?? {
			defs[key] = SynthDef(key, { |bus = 0, fft = 0|
				FFT(fft, In.ar(bus, n).asArray.sum / n, 0.5, 1);
			});
			defs[key]
		}
	}

	// A full scale sine through a Hann window peaks at N/4 in the bin it
	// lands in, so that is where 0 dB is.
	*normalise { ^4 / fftSize }

	// --------------------------------------------------------------- state

	server { ^eq !? (_.server) }

	// Whether a picture is being drawn at all: a window has asked for one,
	// the class is switched on, and the eq is somewhere it can be read.
	visible { ^running and: { ParaEQSpectrum.on } and: { this.prWanted } }

	// The level now at a frequency, in dB - the band it falls in - for a
	// readout that follows the pointer (the eq window's caption)
	levelAt { |freq|
		var n = freqs.size, k;
		if (n < 1 or: { levels.isNil }) { ^nil };
		k = (freq.clip(minFreq, maxFreq) / minFreq).log / (maxFreq / minFreq).log * (n - 1);
		^levels.clipAt(k.round.asInteger)
	}

	prWanted {
		^eq.notNil and: { eq.enabled } and: { eq.analysisAvailable }
	}

	running_ { |bool|
		bool = bool.booleanValue;
		if (bool == running) { ^this };
		running = bool;
		if (running) { this.prStart } { this.prStop };
	}

	running { ^running }

	// The page is looking at another eq now. The analyser moves with it,
	// and the trace starts again rather than sliding over from the old one.
	eq_ { |anEq|
		if (anEq === eq) { ^this };
		this.prFreeNode(true);
		eq = anEq;
		this.prClear;
		if (running) { this.prSync };
	}

	free {
		this.prStop;
		buffer !? (_.free);
		buffer = nil;
	}

	// ------------------------------------------------------------ the node

	prStart {
		var server = this.server;
		if (server.isNil) { ^this };
		this.prClear;
		// fixed, so cmd-period leaves it listening
		responder ?? {
			responder = OSCFunc({ |msg| this.prChunk(msg) }, '/b_setn', server.addr).fix;
		};
		task ?? { this.prStartTask };
		CmdPeriod.add(this);
		this.prSync;
	}

	prStartTask {
		task = Routine({
			var tick = 0;
			var every = (ParaEQSpectrum.pollRate
				/ ParaEQSpectrum.syncRate.clip(0.2, 10)).round.asInteger.max(1);
			loop {
				if ((tick % every) == 0) { this.prSync };
				if (synth.notNil and: { this.visible }) { this.prFetch };
				tick = tick + 1;
				(1 / ParaEQSpectrum.pollRate.clip(1, 60)).wait;
			};
		}).play(AppClock);
	}

	// cmd-period: the clock dropped the task (or will not, if it keeps
	// its clocks: then it is stopped here) and the server takes the
	// analyser node, so both are made again. A buffer or def being waited
	// for died with its clock: those are asked for again too. The task
	// starts at once; a node made before the tree is back gets no frames,
	// and prSync's watchdog makes it again
	cmdPeriod {
		task !? (_.stop);
		task = nil;
		bufPending = false;
		this.server !? { |sv| ParaEQSpectrum.prDefsGone(sv) };
		this.prFreeNode(false);
		if (running) { this.prStartTask };
	}

	prStop {
		CmdPeriod.remove(this);
		task !? (_.stop);
		task = nil;
		responder !? (_.free);
		responder = nil;
		fetching = false;
		this.prFreeNode(true);
		this.prClear;
	}

	// Everything that could have moved under the analyser since the last
	// look: the server going away and coming back, cmd + period taking the
	// tree with it, the eq being switched off, the eq's node being rebuilt
	// somewhere else. Cheap enough to run twice a second, and the only
	// thing that puts the analyser back after any of them.
	prSync {
		var server = this.server, place, tgt, bus, chans;

		if (running.not or: { server.isNil }) { ^this };

		if (server.serverRunning.not) {
			// nothing to free: it went with the server
			synth = nil; trackedTarget = nil; buffer = nil; fetching = false;
			serverWasUp = false;
			^this.prClear;
		};
		if (serverWasUp.not) {			// it has just come up, or come back
			serverWasUp = true;
			synth = nil;
			trackedTarget = nil;
			buffer = nil;
			fetching = false;
			ParaEQSpectrum.prDefsGone(server);
		};

		if (this.visible.not) { ^this.prFreeNode(true) };

		place = eq.analysisPlacement;
		bus = eq.analysisBusIndex;
		chans = eq.analysisChannels;
		if (place.isNil or: { bus.isNil }) { ^this.prFreeNode(false) };
		tgt = place[0];

		// the eq's node was rebuilt, or it was given another bus. Nodes are
		// compared by id and server: a host may answer a new Group object
		// for the same group every time it is asked
		if (synth.notNil and: {
			(trackedTarget != tgt) or: { trackedBus != bus } or: { trackedChannels != chans }
		}) {
			// a node that has gone cannot be told to free the analyser after it
			this.prFreeNode(trackedTarget == tgt or: { tgt.isKindOf(Group) });
		};

		if (buffer.isNil) { ^this.prAllocBuffer(server) };
		if (ParaEQSpectrum.prEnsureDef(server, chans).not) { ^this };
		if (synth.isNil) { ^this.prMakeNode(server, tgt, place[1], bus, chans) };

		// no frame for 2 s, or a node not playing 2 s after it was made: it
		// never started (its target was gone, the tree not back yet after
		// cmd-period) or went. Frames alone cannot tell: they are reads of the
		// buffer, which answers with the node gone. Made again - at most every
		// 2 s, since making it starts both clocks again. Only a node the
		// server said it started is told to free
		if ((Main.elapsedTime - lastFrame) > 2.0 or: {
			server.notified and: { synth.isPlaying.not }
				and: { (Main.elapsedTime - madeAt) > 2.0 }
		}) {
			this.prFreeNode(synth.isPlaying);
			^this.prMakeNode(server, tgt, place[1], bus, chans)
		};

		// a frame should have arrived by now; if none has, let the trace go
		// rather than leave it standing where the sound stopped
		if ((Main.elapsedTime - lastFrame) > 1.0) {
			fetching = false;
			if (levels.any { |db| db > floorDb }) {
				this.prClear;
				action.value(this);
			};
		};
	}

	// Buffer.alloc answers when the server has done it, not when the
	// message is sent, so this waits for that answer and then goes straight
	// on: the analyser is waiting for the buffer.
	prAllocBuffer { |server|
		if (bufPending) { ^this };
		bufPending = true;
		{
			buffer = Buffer.alloc(server, ParaEQSpectrum.fftSize, 1);
			server.sync;
			bufPending = false;
			if (running) { this.prSync };
		}.fork(AppClock);
	}

	prMakeNode { |server, tgt, addAction, bus, chans|
		var sr = (server.sampleRate ? server.options.sampleRate ? 48000).asFloat;
		edges = ParaEQSpectrum.bandBins(sr);
		fetchCount = ParaEQSpectrum.fetchCount(sr);
		frame = Array.newClear(fetchCount);
		fetching = false;
		trackedTarget = tgt;
		trackedBus = bus;
		trackedChannels = chans;
		lastFrame = Main.elapsedTime;
		madeAt = lastFrame;
		synth = Synth(ParaEQSpectrum.defName(chans), [
			\bus, bus,
			\fft, buffer.bufnum
		], tgt, addAction);
		synth.register;		// isPlaying once the server says /n_go
	}

	// `alive` says whether the node is still there to be freed: after a
	// cmd + period it is not, and asking the server to free it again only
	// gets a failure line back - and no /n_end comes to take it off the
	// NodeWatcher, so it is taken off here.
	prFreeNode { |alive = true|
		synth !? { |sy| if (alive) { sy.free } { NodeWatcher.unregister(sy) } };
		synth = nil;
		trackedTarget = nil;
		trackedBus = nil;
		fetching = false;
	}

	// ---------------------------------------------------------- the frames

	// One frame, asked for in as few messages as the server will answer in
	// - b_getn tops out at 1633 values under udp - and all of them sent
	// together. A frame that never comes back is not chased: the next one
	// is along in a moment.
	prFetch {
		var server = this.server;
		var pos = 0, msgs = [];
		if (buffer.isNil or: { server.isNil }) { ^this };
		if (fetching) {
			// a chunk that never came back would otherwise stop the picture
			// for good; udp is allowed to lose one
			if ((Main.elapsedTime - fetchStart) < 0.5) { ^this };
			fetching = false;
		};
		while { pos < fetchCount } {
			var count = min(1633, fetchCount - pos);
			msgs = msgs.add([\b_getn, buffer.bufnum, pos, count]);
			pos = pos + count;
		};
		chunksLeft = msgs.size;
		fetching = true;
		fetchStart = Main.elapsedTime;
		server.listSendBundle(nil, msgs);
	}

	// [\b_setn, bufnum, start, count, values...] - one chunk of the frame
	prChunk { |msg|
		if (fetching.not or: { buffer.isNil } or: { msg[1] != buffer.bufnum }) { ^this };
		frame.overWrite(msg.copyToEnd(4), msg[2]);
		chunksLeft = chunksLeft - 1;
		if (chunksLeft > 0) { ^this };
		fetching = false;
		this.prTake(Main.elapsedTime - lastFrame);
		lastFrame = Main.elapsedTime;
	}

	// The loudest bin in each band, in dB, instant on the way up and slow
	// on the way down so that a peak can be seen rather than merely
	// flickered at, with a slower mark hanging over it. Both falls are per
	// second and the frames are timed, so it looks the same however fast
	// they arrive.
	//
	// The buffer holds DC at 0, nyquist at 1, and bin k as the pair at 2k
	// and 2k + 1, which is why the loop steps in twos from there.
	prTake { |dt|
		var norm = ParaEQSpectrum.normalise;
		var step = dt.clip(1 / ParaEQSpectrum.pollRate.clip(1, 60), 0.5);
		var fall = ParaEQSpectrum.fallDb * step;
		var peakFall = ParaEQSpectrum.peakFallDb * step;
		var lo = ParaEQSpectrum.floorDb;
		var trim = ParaEQSpectrum.dbTrim;
		edges.do { |pair, i|
			var best = 0, db;
			(pair[0] * 2).forBy((pair[1] * 2) + 1, 2) { |j|
				var re = frame[j] ? 0, im = frame[j + 1] ? 0;
				var p = (re * re) + (im * im);
				if (p > best) { best = p };
			};
			db = ((best.sqrt * norm).ampdb + trim).max(lo);
			levels[i] = db.max((levels[i] - fall).max(lo));
			peaks[i] = levels[i].max((peaks[i] - peakFall).max(lo));
		};
		action.value(this);
	}

	prClear {
		var lo = ParaEQSpectrum.floorDb;
		levels = Array.fill(freqs.size, { lo });
		peaks = Array.fill(freqs.size, { lo });
	}

	// ------------------------------------------------------- the shared defs

	// Built and sent the first time anything wants one, not at startup: a
	// session that never opens an eq page never pays for it. Adding a def
	// is asynchronous, so nothing is played until the server has answered.
	*prEnsureDef { |server, numChannels|
		var key = this.defName(numChannels);
		var ready = defsReady[key] ?? { defsReady[key] = IdentitySet.new; defsReady[key] };
		var pending = defsPending[key] ?? { defsPending[key] = IdentitySet.new; defsPending[key] };
		if (ready.includes(server)) { ^true };
		if (pending.includes(server)) { ^false };
		pending.add(server);
		{
			var d = this.synthDef(numChannels);
			d.add;
			if (server != Server.default) { d.send(server) };
			server.sync;
			ready.add(server);
			pending.remove(server);
		}.fork(AppClock);
		^false
	}

	*prDefsGone { |server|
		defsReady.do { |set| set.remove(server) };
		defsPending.do { |set| set.remove(server) };
	}

	printOn { |stream|
		stream << "ParaEQSpectrum(" << (eq !? (_.label)) << ", "
			<< if (running) { "on" } { "off" } << ")"
	}
}
