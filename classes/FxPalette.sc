// =====================================================================
// FxPalette - the colours and fonts every effect window is drawn in
//
// The same values the module family, Insight and ServerPanel use, so an
// eq opened from a pad, from the master chain or from an Ndef looks like
// one instrument. Kept here so this repository depends on nothing.
//
//   FxPalette.bg
//   FxPalette.eqBand(2)
//   FxPalette.label("freq", 30)
//   FxPalette.button("flat", 40, { eq.flat })
// =====================================================================

FxPalette {
	classvar <bg, <panel, <text, <dim, <accent;
	classvar <wave, <waveDim, <waveBg;
	classvar <flash, <handle, <sel, <good, <clip, <peak;
	classvar <eqBands;
	classvar <card, <control, <controlHi;		// sc-fx-ui surfaces: a group's card, a control's body
	classvar <gain, <cut, <ivory, identities, eqHandles;
	classvar font, smallFont, boldFont, monoFont;

	*initClass {
		bg       = Color(0.13, 0.13, 0.15);
		panel    = Color(0.20, 0.20, 0.24);
		text     = Color(0.86, 0.87, 0.89);
		dim      = Color(0.55, 0.56, 0.62);
		accent   = Color(0.98, 0.65, 0.20);
		wave     = Color(0.42, 0.76, 0.95);
		waveDim  = Color(0.30, 0.33, 0.40);
		waveBg   = Color(0.09, 0.10, 0.12);
		flash    = Color(0.98, 0.65, 0.20);
		handle   = Color(0.95, 0.95, 0.97);
		sel      = Color(0.42, 0.76, 0.95);
		good     = Color(0.40, 0.86, 0.60);
		clip     = Color(0.95, 0.35, 0.30);
		peak     = Color(0.95, 0.96, 0.98);
		card     = Color(0.17, 0.17, 0.20);
		control  = Color(0.25, 0.25, 0.29);
		controlHi = Color(0.33, 0.33, 0.38);

		// sc-fx-ui colours with one meaning each (never accent, clip, Tidal green or wave):
		gain     = Color(0.92, 0.50, 0.96);	// gain change: what a dynamics effect does to the level
		cut      = Color(0.72, 0.75, 0.84);	// what an eq's cuts take away
		ivory    = Color(0.96, 0.92, 0.80);	// the eq's own colour: the summed curve, what you hear
		// ParaEQ's handles, low to high, then its two cuts
		eqHandles = [
			Color(0.70, 0.54, 0.98), Color(0.40, 0.86, 0.80), Color(0.76, 0.90, 0.36),
			Color(0.95, 0.82, 0.36), Color(0.95, 0.52, 0.74), cut, cut
		];
		// a family's identity: what the effect itself adds (a tail, echoes, grains,
		// a held spectrum, ringing strings, gain change) - see the sc-fx-ui skill
		identities = (
			reverb:    Color(0.72, 0.60, 0.95),	// violet: a tail
			hall:      Color(0.72, 0.60, 0.95),
			plate:     Color(0.80, 0.78, 0.96),	// pale steel-lavender: a ringing sheet
			coil:      Color(0.60, 0.58, 0.96),	// indigo-violet: a spring's chirps
			shimmer:   Color(0.90, 0.62, 0.96),	// orchid-pink: a tail that climbs
			delay:     Color(0.40, 0.86, 0.80),	// teal: repeats
			spectral:  Color(0.95, 0.52, 0.74),	// pink: a held, moved spectrum
			granular:  Color(0.88, 0.80, 0.60),	// sand: grains
			repeat:    Color(0.95, 0.84, 0.38),	// yellow: a caught slice
			resonator: Color(0.62, 0.62, 0.98),	// lilac-indigo: ringing strings
			pitch:     Color(0.76, 0.90, 0.36),	// lime: a shifted partial
			stereo:    Color(0.98, 0.78, 0.58),	// apricot: the image, the side an imager adds
			modulation: Color(0.96, 0.58, 0.86),	// orchid-rose: voices that waver (chorus, flanger)
			dynamics:  gain,
			eq:        ivory
		);

		// The eq bands read left to right, so the colour says which end of
		// the spectrum a handle belongs to before you have read its name.
		// After them the two cuts, in colours off that sweep, since they
		// take away rather than lift.
		eqBands  = [
			Color(0.42, 0.76, 0.95),
			Color(0.40, 0.86, 0.80),
			Color(0.60, 0.88, 0.48),
			Color(0.95, 0.82, 0.36),
			Color(0.98, 0.65, 0.20),
			Color(0.72, 0.60, 0.95),
			Color(0.95, 0.52, 0.74)
		];
	}

	*eqBand { |i| ^eqBands.wrapAt(i.asInteger) }

	*eqHandle { |i| ^eqHandles.clipAt(i.asInteger) }

	// a family's identity colour (FxPalette.identity(\reverb)), or nil
	*identity { |key| ^key !? { identities[key.asSymbol] } }

	// ---------------------------------------------------------------- fonts

	*font { ^font ?? { font = Font(Font.defaultSansFace, 11) } }
	*smallFont { ^smallFont ?? { smallFont = Font(Font.defaultSansFace, 9) } }
	*boldFont { ^boldFont ?? { boldFont = Font(Font.defaultSansFace, 12, true) } }
	*monoFont { ^monoFont ?? { monoFont = Font(Font.defaultMonoFace, 11) } }

	// -------------------------------------------------------------- widgets

	// the small dim label every row starts with
	*label { |str, width, align = \right|
		var t = StaticText().string_(str).stringColor_(dim)
			.font_(this.smallFont).align_(align);
		width !? { t.fixedWidth_(width) };
		^t
	}

	// a one state button in the house colours
	*button { |str, width, func|
		var b = Button().states_([[str, text, panel]]).canFocus_(false)
			.action_({ func.value });
		width !? { b.fixedWidth_(width) };
		^b
	}

	// the dark palette, so the Qt widgets that are not coloured by hand -
	// sliders, menus, number boxes - come out like the rest
	*darken { |window|
		var v = window.tryPerform(\view) ? window;
		if (GUI.id == \qt) { v.palette = QPalette.dark };
		v.background = bg;
		^window
	}

	// ---------------------------------------------------- values as words

	// a colour at a fraction of its alpha, leaving the original alone
	*fade { |c, a = 1| ^Color(c.red, c.green, c.blue, c.alpha * a) }

	// x with exactly dec decimals: fixed(0.3, 2) is "0.30"
	*fixed { |x, dec = 0|
		var m, q, neg, whole, frac, f;
		dec = dec.asInteger.max(0);
		m = 10 ** dec;
		q = (x.abs * m).round(1);
		neg = (x < 0) and: { q > 0 };
		whole = (q / m).floor.asInteger;
		frac = (q - (whole * m)).round(1).asInteger.asString;
		if (dec > 0 and: { frac.size < dec }) { frac = String.fill(dec - frac.size, $0) ++ frac };
		f = if (dec > 0) { "." ++ frac } { "" };
		^(if (neg) { "-" } { "" }) ++ whole.asString ++ f
	}

	// three significant figures
	*sig3 { |x|
		var a = x.abs;
		^case
		{ a >= 100 } { this.fixed(x, 0) }
		{ a >= 10 } { this.fixed(x, 1) }
		{ true } { this.fixed(x, 2) }
	}

	// ------------------------------------------------------- intervals
	//
	// A parameter's interval is the amount someone usually moves it by: a dB
	// for a level, a semitone for a frequency, a hundredth for a mix. An arrow
	// key on a selected knob moves it by one (shift: a tenth). It is a number
	// in the parameter's own unit, or a way of stepping whose size depends on
	// where the value is:
	//
	//   \db         a dB, for a gain kept as an amplitude (below -60 dB: off)
	//   \semitone   a twelfth of an octave: small steps low, large ones high
	//   \ratio      about a twentieth of the value, on round numbers
	//               (0.05 up to 2, 0.1 up to 5, 0.25 up to 10, and so on)
	//   a Function  { |value, dir, fine| the new value }
	//
	// *intervalFor is what a parameter gets when its table says nothing.
	*intervalFor { |spec, box|
		var units = (spec.units ? "").asString, range = (spec.maxval - spec.minval).abs;
		^case
		// a grid of its own, when it is a coarse one (counts, steps): a fine grid
		// (a dB kept to tenths) only rounds what the interval gives
		{ spec.step > 0 and: { spec.step >= (range / 100) } } { spec.step }
		{ units.beginsWith("dB") or: { units == "LUFS" } or: { box == \db } } { 1 }
		{ spec.warp.isKindOf(DbFaderWarp) } { 1 }
		{ spec.warp.isKindOf(FaderWarp) } { \db }
		// an audible frequency; a slow one (an lfo's rate) steps like a time
		{ units == "Hz" and: { spec.warp.isKindOf(ExponentialWarp) } and: { spec.maxval > 100 } } { \semitone }
		{ #["st", "midi", "bpm", "ct"].includesEqual(units) or: { box == \note } } { 1 }
		{ box == \pct } { min(0.01, this.nice(range / 100)) }
		{ units == "%" } { 1 }
		{ spec.warp.isKindOf(LinearWarp).not } { \ratio }
		{ true } { this.nice(range / 100) }
	}

	// a step is never smaller than what the value text can show: a time reads
	// in tenths of a millisecond under 10 ms, milliseconds under a second,
	// hundredths of a second above
	*prSeen { |step, value, spec, box|
		var v = value.abs;
		if (box != \ms and: { (spec.units ? "").asString != "s" }) { ^step };
		^step.max(case { v < 0.01 } { 0.0001 } { v < 1 } { 0.001 } { true } { 0.01 })
	}

	// the nearest of 1, 2, 5 times a power of ten
	*nice { |x|
		var e, f;
		if (x <= 0) { ^0 };
		e = 10 ** x.log10.floor;
		f = x / e;
		^e * case { f < 1.5 } { 1 } { f < 3.5 } { 2 } { f < 7.5 } { 5 } { true } { 10 }
	}

	// `value` one interval up (dir 1) or down (-1), inside the spec. A number
	// interval lands on its own grid: 1 dB steps from -6.3 go to -5, -4...
	*nudge { |value, dir, spec, interval, box, fine = false|
		var f = if (fine) { 0.1 } { 1 }, range = (spec.maxval - spec.minval).abs;
		var new, step, m, e, r, db;
		interval = interval ?? { this.intervalFor(spec, box) };
		if (interval.isKindOf(Function)) { ^spec.constrain(interval.value(value, dir, fine)) };
		new = switch (interval,
			\db, {
				db = value.max(1e-9).ampdb;
				if (db < -60) { if (dir > 0) { -60.dbamp } { 0 } } {
					db = (db + (dir * f)).round(f);
					if (db < -60) { 0 } { db.dbamp }
				}
			},
			\semitone, { value * (2 ** (dir * f / 12)) },
			\ratio, {
				m = max(value.abs, range * 0.001);
				if (dir < 0) { m = m * 0.999 };		// down from 2 is the step below 2
				e = 10 ** m.log10.floor;
				r = m / e;
				step = e * (case { r < 2 } { 0.05 } { r < 5 } { 0.1 } { true } { 0.25 });
				// a range that starts at nothing: off the bottom in steps worth making
				if (spec.minval <= 0 and: { spec.maxval > 0 }) { step = step.max(this.nice(range / 1000)) };
				step = this.prSeen(step, value, spec, box) * f;
				(value + (dir * step)).round(step)
			},
			{
				step = this.prSeen(interval, value, spec, box) * f;
				if (spec.step > 0) { step = step.max(spec.step) };
				(value + (dir * step)).round(step)
			}
		);
		^spec.constrain(new)
	}

	// a parameter's value as it is read under its knob, with its unit:
	// "375 ms", "1.80 kHz", "-6.0 dB", "35 %". box is the table's (\pct, \ms);
	// floorText is what the bottom of the range says when it means off
	*format { |v, units, box, dec, bipolar = false, floorText, spec|
		var u = units ? "", sign;
		if (v.isNil) { ^"" };
		if (floorText.notNil and: { spec.notNil } and: { v <= spec.minval }) { ^floorText };
		sign = { |str, x| if (bipolar and: { x > 0 }) { "+" ++ str } { str } };
		^case
		{ box == \pct } { sign.(this.fixed(v * 100, dec ? 0), v) ++ " %" }
		{ box == \ms or: { u == "s" } } {
			if (v.abs < 1) {
				this.fixed(v * 1000, if (v.abs < 0.01) { 1 } { 0 }) ++ " ms"
			} {
				this.fixed(v, 2) ++ " s"
			}
		}
		{ u == "Hz" } {
			// round first: 999.6 must read 1.00 kHz, not 1000 Hz; signed when bipolar or negative
			var a = v.abs, str;
			str = if (a.round(if (a < 100) { 0.01 } { 1 }) >= 1000) { this.sig3(a / 1000) ++ " kHz" } {
				this.fixed(a, case { a < 10 } { 2 } { a < 100 } { 1 } { true } { 0 }) ++ " Hz"
			};
			case { v < 0 } { "-" ++ str } { bipolar and: { v > 0 } } { "+" ++ str } { true } { str }
		}
		{ box == \note } { this.midiName(v) }
		{ u == ":1" } { this.fixed(v, if (v < 10) { 1 } { 0 }) ++ ":1" }
		{ u == "dB" } { sign.(this.fixed(v, dec ? 1), v) ++ " dB" }
		{ u.isEmpty } { sign.(this.fixed(v, dec ? 2), v) }
		{ true } { sign.(this.fixed(v, dec ? 1), v) ++ " " ++ u }
	}

	// what someone typed into a value, back to the parameter's own unit:
	// "2k" 2000 Hz, "350ms" 0.35 s, "35" 0.35 for a percent; nil if it is no number
	*parse { |str, units, box|
		var s = str.asString.toLower.reject(_.isSpace), num, u = units ? "", i = 0, midi;
		if (s.isEmpty) { ^nil };
		// a Tidal note name: a frequency where the unit is Hz, a note number for \note
		if (u == "Hz" or: { box == \note }) {
			midi = this.parseNote(s);
			midi !? { ^if (box == \note) { midi } { midi.midicps } };
		};
		if ("^[-+]?[0-9.]".matchRegexp(s).not) {
			^if (s == "off" or: { s == "-inf" }) { -inf } { nil }
		};
		// the number is the front of it: String:asFloat reads "2.5k" as 2, not 2.5
		while {
			i < s.size and: {
				s[i].isDecDigit or: { s[i] == $. } or: { (i == 0) and: { (s[i] == $-) or: { s[i] == $+ } } }
			}
		} { i = i + 1 };
		num = s.copyRange(0, i - 1).asFloat;
		^case
		{ box == \pct } { num / 100 }
		{ box == \ms or: { u == "s" } } {
			case
			{ s.endsWith("ms") } { num / 1000 }
			{ s.endsWith("s") } { num }
			{ box == \ms } { num / 1000 }
			{ true } { num }
		}
		{ s.endsWith("k") or: { s.endsWith("khz") } } { num * 1000 }
		{ true } { num }
	}

	// ------------------------------------------------------- notes, as Tidal says them

	// Tidal's names: note 0 is c5 is midi 60, sharps are s ("cs5"), so what a
	// window shows is what a student types (# note "e2")
	*midiName { |midi|
		var n = midi.round.asInteger;
		^#["c", "cs", "d", "ds", "e", "f", "fs", "g", "gs", "a", "as", "b"][n % 12] ++ (n div: 12).asString
	}

	*noteName { |freq| ^this.midiName(freq.max(1).cpsmidi) }

	// "e2" "cs5" "c#5" "ef3" "bb2" "a" -> a midi number (octave 5 when not said), or nil
	*parseNote { |str|
		var s = str.asString.toLower.reject(_.isSpace), pc, idx, i = 1, acc = 0, oct = 5, rest;
		if (s.isEmpty) { ^nil };
		idx = "abcdefg".indexOf(s[0]);
		if (idx.isNil) { ^nil };
		pc = #[9, 11, 0, 2, 4, 5, 7][idx];
		if (i < s.size) {
			case
			{ (s[i] == $s) or: { s[i] == $# } } { acc = 1; i = i + 1 }
			{ ((s[i] == $f) or: { s[i] == $b }) and: { (i + 1) < s.size } and: { s[i + 1].isDecDigit or: { s[i + 1] == $- } } } { acc = -1; i = i + 1 }
			{ ((s[i] == $f) or: { s[i] == $b }) and: { (i + 1) == s.size } } { acc = -1; i = i + 1 };
		};
		rest = s.copyRange(i, s.size - 1);
		if (rest.notEmpty) {
			if ("^-?[0-9]+$".matchRegexp(rest).not) { ^nil };
			oct = rest.asInteger;
		};
		^(oct * 12) + pc + acc
	}

	// a word for where a frequency sits, for readouts and hints
	*regionName { |freq|
		^case
		{ freq < 60 } { "sub" }
		{ freq < 250 } { "bass" }
		{ freq < 500 } { "low mids" }
		{ freq < 2000 } { "mids" }
		{ freq < 4000 } { "high mids" }
		{ freq < 6000 } { "presence" }
		{ true } { "air" }
	}
}
