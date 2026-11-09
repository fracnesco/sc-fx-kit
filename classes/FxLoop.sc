// =====================================================================
// FxLoop - a delay line with feedback that needs no LocalIn
//
// A SynthDef can have one LocalIn. Two effects in one graph - an Ndef with a
// delay and a distortion in it - could not both use it, so the family's
// feedback goes through a buffer: one LocalBuf per channel, written at a
// phasor, read as far behind it as you like, as many times as you like.
//
//   l = FxLoop(2, 4);                  // two channels, four seconds
//   y = l.read(0.25);                  // read first
//   l.write(sig + (y * 0.6));          // then write what the reads made
//
// Used inside a SynthDef function, once per loop: it makes UGens. The shortest
// delay is one control block plus the interpolation's margin (`*minTime`),
// since a block is written and read as one; anything shorter gets that.
//
// The memory comes out of the server's real time pool: 4 bytes a frame, so
// four seconds of stereo at 48 kHz is 1.5 MB of the 8 MB a default server has.
// =====================================================================

FxLoop {
	var <numChannels, <maxTime, <buffers, <frames, <phase;

	*new { |numChannels = 1, maxTime = 1, run = 1|
		^super.newCopyArgs(numChannels.asInteger, maxTime).init(run)
	}

	// run: 1 goes on writing, 0 stands still, for a loop that freezes
	init { |run|
		frames = (SampleRate.ir * maxTime).ceil;
		buffers = Array.fill(numChannels, { LocalBuf(frames, 1).clear });
		phase = Phasor.ar(0, run, 0, frames);
	}

	*minTime { ^ControlDur.ir + (4 * SampleDur.ir) }

	// How far behind the write head, in seconds: one number or signal, or one
	// per channel. Interpolated, so it can move. Answers an Array of channels.
	read { |time, interpolation = 4|
		var t = time.asArray.wrapExtend(numChannels);
		^buffers.collect { |b, i|
			var back = t[i].clip(this.class.minTime, maxTime - this.class.minTime) * SampleRate.ir;
			BufRd.ar(1, b, (phase - back).wrap(0, frames), 1, interpolation)
		}
	}

	// the frame the head is on, for something that wants to remember where
	writePhase { ^phase }

	// an Array of channels, cleaned: one NaN here would otherwise go round for
	// as long as the node lives
	write { |sig|
		sig.asArray.wrapExtend(numChannels).do { |x, i|
			BufWr.ar(Sanitize.ar(x), buffers[i], phase, 1);
		};
	}
}


// =====================================================================
// FxLfo - one low frequency oscillator, seven shapes, chosen by a control
//
//   lfo = FxLfo.kr(p[\lfoShape], p[\lfoRate], phase: 0.25);    // -1..1
//   lfo = FxLfo.kr(p[\lfoShape], p[\lfoRate], 0, p[\lfoSync]);  // or on a cycle clock
//   FxLfo.at(\triangle, 0.25);                                 // the same in numbers
//
// `at` is for a display. The last two shapes are random, and it draws them
// as the flat line they average to.
//
// sync is an index into `syncNames`: 0 is free (rate in Hz), the others are a
// period in cycles of a clock the graph is told about through three controls
// every effect of one graph shares: fxCps (cycles a second), fxCycle (where
// the clock was when told) and fxSync (a trigger, sent with fxCycle). From
// the moment of the trigger the position runs on at fxCps, so a clock told
// now and then - SuperDirtGUI tells it from Tidal's events, every half cycle
// or so - keeps the lfo on the cycle's phase, not just its speed. Told
// nothing, it runs at fxCps' default, 0.5625 (135 bpm at 4 beats a cycle).
// =====================================================================

FxLfo {
	*shapes { ^#[\sine, \triangle, \sawUp, \sawDown, \square, \sampleHold, \noise] }

	// a sync menu's items, and the period each is in cycles (free's is unused)
	// words, not fractions: a Tidal pattern says them, and its mininotation reads
	// 1/4 as 1 slowed by 4. four = one period every four cycles ... twelfth = a
	// period every twelfth of a cycle
	*syncNames { ^#[\free, \four, \two, \one, \half, \quarter, \eighth, \sixteenth, \third, \sixth, \twelfth] }
	*syncCycles { ^#[1, 4, 2, 1, 0.5, 0.25, 0.125, 0.0625, 0.33333333333333, 0.16666666666667, 0.083333333333333] }

	// the clock's three controls, shared by name within a graph
	*clock {
		var cps = NamedControl.kr(\fxCps, 0.5625);
		var cycle = NamedControl.kr(\fxCycle, 0);
		var trig = NamedControl.tr(\fxSync, 0);
		^[cps, cycle + (Sweep.kr(trig) * cps)]
	}

	*kr { |shape = 0, rate = 1, phase = 0, sync = 0|
		var ph, wrapped, isSync, cps, pos, cycles;
		if (sync == 0) {
			// free, as it always was: no clock controls in the graph
			ph = (Phasor.kr(0, rate * ControlDur.ir, 0, 1) + phase).wrap(0, 1);
		} {
			#cps, pos = this.clock;
			cycles = Select.kr(sync, this.syncCycles);
			isSync = sync > 0;
			ph = Select.kr(isSync, [
				Phasor.kr(0, rate * ControlDur.ir, 0, 1),
				pos / cycles
			]);
			ph = (ph + phase).wrap(0, 1);
			rate = Select.kr(isSync, [rate, cps / cycles]);
		};
		wrapped = ph < Delay1.kr(ph);
		^Select.kr(shape, [
			(ph * 2pi).sin,
			1 - (4 * (ph - 0.5).abs),
			(ph * 2) - 1,
			1 - (ph * 2),
			(ph < 0.5) * 2 - 1,
			Latch.kr(WhiteNoise.kr, wrapped + Impulse.kr(0)),
			LFNoise2.kr(rate.max(0.01))
		])
	}

	*at { |shape, ph|
		ph = ph.wrap(0, 1);
		^switch (shape.asSymbol,
			\sine, { (ph * 2pi).sin },
			\triangle, { 1 - (4 * (ph - 0.5).abs) },
			\sawUp, { (ph * 2) - 1 },
			\sawDown, { 1 - (ph * 2) },
			\square, { if (ph < 0.5) { 1 } { -1 } },
			{ 0 }
		)
	}
}


// =====================================================================
// FxKit - the small things more than one effect wants
//
//   FxKit.width(sig, 1.5);                     // wider than it came in
//   FxKit.biquadMag(\lp, 1000, 1, [500], 48000);  // BLowPass's gain there, as a ratio
//   spec.warp = FxCubeWarp(spec);              // fine near the middle, for a bipolar knob
// =====================================================================

FxKit {
	// each channel moved away from the middle of them all: 0 is mono, 1 as it
	// came in. Nothing to do on one channel
	*width { |sig, amount|
		var mid;
		sig = sig.asArray;
		if (sig.size < 2) { ^sig };
		mid = sig.sum / sig.size;
		^sig.collect { |x| mid + ((x - mid) * amount) }
	}

	// The gain of BLowPass or BHiPass at some frequencies, in the numbers the
	// UGens run on, so a window's curve is what the server does.
	*biquadMag { |type, f0, rq, freqs, sr = 48000|
		var w0 = 2pi * f0.clip(1, sr * 0.49) / sr;
		var alpha = w0.sin * 0.5 * rq, c = w0.cos;
		var b0, b1, b2, a0 = 1 + alpha, a1 = -2 * c, a2 = 1 - alpha;
		if (type == \lp) { b0 = (1 - c) * 0.5; b1 = 1 - c; b2 = b0 } {
			b0 = (1 + c) * 0.5; b1 = (1 + c).neg; b2 = b0
		};
		^freqs.collect { |f|
			var w = 2pi * f / sr;
			var nr = b0 + (b1 * w.cos) + (b2 * (2 * w).cos);
			var ni = (b1 * w.sin) + (b2 * (2 * w).sin);
			var dr = a0 + (a1 * w.cos) + (a2 * (2 * w).cos);
			var di = (a1 * w.sin) + (a2 * (2 * w).sin);
			((nr.squared + ni.squared) / (dr.squared + di.squared).max(1e-20)).sqrt
		}
	}
}


// A knob from -hi to hi that moves in small steps near 0 and large ones at the
// ends: a frequency shift wants 0.1 Hz at the middle and 500 Hz at the edge.
FxCubeWarp : Warp {
	map { |value|
		var x = (value * 2) - 1;
		^x.cubed * spec.maxval
	}

	unmap { |value|
		var x = value / spec.maxval;
		^(x.abs ** (1/3) * x.sign + 1) * 0.5
	}
}
