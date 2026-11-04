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
//   FxLfo.at(\triangle, 0.25);                                 // the same in numbers
//
// `at` is for a display. The last two shapes are random, and it draws them
// as the flat line they average to.
// =====================================================================

FxLfo {
	*shapes { ^#[\sine, \triangle, \sawUp, \sawDown, \square, \sampleHold, \noise] }

	*kr { |shape = 0, rate = 1, phase = 0|
		var ph = (Phasor.kr(0, rate * ControlDur.ir, 0, 1) + phase).wrap(0, 1);
		var wrapped = ph < Delay1.kr(ph);
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
