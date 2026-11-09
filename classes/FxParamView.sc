// =====================================================================
// FxParamView - the base of an effect's display
//
// One UserView that draws the effect and is usually a way of playing it too.
// A subclass writes prDraw, using bw and bh for its size, and whichever of
// prMouseDown, prMouseMove, prWheel and hint it wants; the panel keeps it
// refreshed, on every change and on every meter reply while it is on screen.
// An effect that wants its output's spectrum drawn answers true to
// *wantsSpectrum and calls prDrawSpectrum: that is ParaEQSpectrum, which runs
// only while the view does, and reads wherever the unit says it can be read.
//
// FxLevelBars, below, is the pair of bars the panel puts next to it.
// =====================================================================

FxParamView {
	var <view, <unit, <spectrum, running = false;
	var bw = 1, bh = 1;

	*new { |unit, parent| ^super.new.init(unit, parent) }

	*minHeight { ^170 }
	*wantsSpectrum { ^false }

	init { |argUnit, parent|
		unit = argUnit;
		view = UserView(parent).minHeight_(this.class.minHeight).minWidth_(200);
		view.background_(FxPalette.waveBg);
		view.drawFunc = { |v|
			this.prMeasure;
			if (unit.notNil) { this.prDraw };
		};
		view.mouseDownAction = { |v, x, y, mod, button, count|
			this.prMeasure;
			if (unit.notNil) { this.prMouseDown(x, y, mod, count) };
			true
		};
		view.mouseMoveAction = { |v, x, y, mod|
			if (unit.notNil) { this.prMouseMove(x, y, mod) };
			true
		};
		view.mouseUpAction = { if (unit.notNil) { this.prMouseUp }; true };
		view.mouseWheelAction = { |v, x, y, mod, xd, yd|
			this.prMeasure;
			if (unit.notNil) { this.prWheel(x, y, yd) };
			true
		};
		if (this.class.wantsSpectrum) {
			spectrum = ParaEQSpectrum(unit);
			spectrum.action = { { this.refresh }.defer };
		};
		^this
	}

	unit_ { |u|
		unit = u;
		spectrum !? { |s| s.eq = u };
		this.refresh;
	}

	running_ { |bool|
		running = bool;
		spectrum !? { |s| s.running = bool };
	}

	running { ^running }

	refresh { if (view.notNil and: { view.isClosed.not }) { view.refresh } }

	remove {
		spectrum !? (_.free);
		spectrum = nil;
	}

	prMeasure {
		var b = view.bounds;
		bw = b.width.max(1);
		bh = b.height.max(1);
	}

	// what the hint bar says over the display; x and y are the pointer's, in
	// the view, so a display can name what is under it (nil when not known)
	hint { |x, y| ^"" }

	// called on every meter reply, before the refresh: a display that draws a
	// live trace (a reverb's ringing out) records unit.outLevel here
	levelsArrived { }

	prDraw { }
	prMouseDown { |x, y, mod, count| }
	prMouseMove { |x, y, mod| }
	prMouseUp { }
	prWheel { |x, y, delta| }

	// ------------------------------------------------------------- spectrum

	*freqToX { |f, width, lo = 20, hi = 20000| ^f.explin(lo, hi, 0, width, \minmax) }
	*xToFreq { |x, width, lo = 20, hi = 20000| ^x.linexp(0, width, lo, hi, \minmax) }

	// the output's spectrum, filled, across the whole view
	prDrawSpectrum { |colour|
		var levels, freqs, lo = ParaEQSpectrum.floorDb, hi = ParaEQSpectrum.topDb;
		if (spectrum.isNil or: { spectrum.visible.not }) { ^this };
		levels = spectrum.levels;
		freqs = spectrum.freqs;
		if (levels.isNil) { ^this };
		Pen.fillColor = colour ? FxPalette.wave.copy.alpha_(0.3);
		Pen.moveTo(0 @ bh);
		levels.do { |db, i|
			Pen.lineTo(FxParamView.freqToX(freqs[i], bw) @ db.linlin(lo, hi, bh, 0, \minmax));
		};
		Pen.lineTo(bw @ bh);
		Pen.fill;
	}

	// a small dim line of text in a corner
	prCaption { |str, x = 6, y = 4, colour|
		Pen.font = FxPalette.smallFont;
		Pen.color = colour ? FxPalette.dim;
		Pen.stringAtPoint(str, x @ y);
	}
}


FxLevelBars {
	var <view, <>unit, holdIn = 0, holdOut = 0;

	*new { |unit| ^super.new.init(unit) }

	init { |argUnit|
		unit = argUnit;
		view = UserView().fixedWidth_(34);
		view.background_(FxPalette.waveBg);
		view.drawFunc = { |v| this.prDraw(v) };
		view.mouseDownAction = { holdIn = 0; holdOut = 0; this.refresh; true };
		^this
	}

	refresh { if (view.notNil and: { view.isClosed.not }) { view.refresh } }

	prDraw { |v|
		var b = v.bounds, h = b.height, top, bar;
		var toY = { |amp| amp.max(1e-5).ampdb.linlin(-60, 6, h, 0, \minmax) };
		var inL = unit !? (_.inLevel) ? 0, outL = unit !? (_.outLevel) ? 0;
		holdIn = max(holdIn * 0.995, inL);
		holdOut = max(holdOut * 0.995, outL);
		bar = { |x, level, hold, colour|
			top = toY.(level);
			Pen.fillColor = if (level > 1) { FxPalette.clip } { colour };
			Pen.fillRect(Rect(x, top, 13, h - top));
			Pen.fillColor = FxPalette.peak;
			Pen.fillRect(Rect(x, toY.(hold), 13, 1));
		};
		bar.(2, inL, holdIn, FxPalette.waveDim);
		bar.(18, outL, holdOut, FxPalette.wave);
	}
}
