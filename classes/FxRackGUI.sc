// =====================================================================
// FxRackCard - a rack in a strip; FxRackPanel - a rack in a window
//
// In a strip (FxStrip) a rack is a bracket: its own card - on/off, how late
// it is, live, a list of its chains with level, align, mute and solo - and
// then, inside the bracket, the cards of the chain picked in the list, with
// a + to add to that chain. Click a chain to see its effects; right click
// it to rename it or take it out.
//
//   [ (o) rack  12.0 ms  live  +  ↗ x ] [ compressor ] [ eq ] [+]
//   [  crush  comp → eq   ====  a m s ]
//   [  dry    -           ====  a m s ]
//
// In a window (the ↗, or rack.gui) every chain is a row: its name, level,
// align, mute, solo at the left, its effects as a strip at the right.
// =====================================================================

FxRackCard {
	classvar <>rowHeight = 22;

	var <unit, <chain, <strip, <view;
	var face, power, infoText, liveSwitch, addBtn, openBtn, dropBtn, list, inner, innerAdd;
	var ctrl, chainCtrl, shownChain, scroll = 0, dragging, hints;

	asView { ^view }

	*new { |unit, chain, strip| ^super.newCopyArgs(unit, chain, strip).init }

	*levelSpec { ^ControlSpec(-60, 6, \lin, 0, 0, "dB") }
	// the bar: more room near the top, where levels are set
	*levelToBar { |db| ^((db + 60) / 66).clip(0, 1).squared }
	*barToLevel { |x| ^(x.clip(0, 1).sqrt * 66) - 60 }

	init {
		var colour = unit.class.identityColour;
		shownChain = unit.branches[unit.shown.clip(0, unit.branches.size - 1)].chain;
		inner = shownChain.units.collect { |u| strip.prCardFor(u, shownChain) };
		power = FxPower().action_({ |p| unit.enabled = p.value });
		infoText = StaticText().font_(FxPalette.smallFont).stringColor_(FxPalette.dim);
		liveSwitch = FxSwitch(["live"], true).action_({ |s| unit.live = s.value });
		addBtn = FxButton("+", { unit.shown = unit.addBranch.index; this.prRebuild }, 20);
		openBtn = FxButton("↗", { unit.gui }, 20);
		dropBtn = FxButton("x", { chain.remove(unit) }, 20);
		list = UserView();
		list.drawFunc = { this.prDrawList };
		list.mouseDownAction = { |v, x, y, mod, button, count| this.prDown(x, y, button, count); true };
		list.mouseMoveAction = { |v, x, y| this.prMove(x); true };
		list.mouseUpAction = { dragging = nil; true };
		list.mouseWheelAction = { |v, x, y, mod, dx, dy|
			scroll = (scroll - dy.sign).clip(0, (unit.branches.size - this.prVisible).max(0));
			list.refresh;
			true
		};
		face = UserView().fixedWidth_(300);
		face.drawFunc = { |v|
			Pen.fillColor = FxPalette.card;
			Pen.addRoundedRect(v.bounds.moveTo(0, 0), 4, 4);
			Pen.fill;
		};
		face.layout = VLayout(
			HLayout(power.view, StaticText().string_("rack").font_(FxPalette.font).stringColor_(colour),
				[infoText, stretch: 1], liveSwitch.view, addBtn.view, openBtn.view, dropBtn.view).margins_(0).spacing_(4),
			[list, stretch: 1]
		).margins_([8, 5, 6, 4]).spacing_(4);
		// + for the chain shown
		innerAdd = UserView().fixedWidth_(30);
		innerAdd.drawFunc = { |v|
			var r = v.bounds.moveTo(0, 0).insetBy(0.5, 0.5);
			Pen.strokeColor = FxPalette.fade(FxPalette.dim, 0.6);
			Pen.width = 1;
			Pen.lineDash = FloatArray[4, 3];
			Pen.addRoundedRect(r, 4, 4);
			Pen.stroke;
			Pen.stringCenteredIn("+", r, FxPalette.font, FxPalette.dim);
		};
		innerAdd.mouseDownAction = { strip.prAddMenu(shownChain); true };
		// the bracket round the rack and what is in the chain shown
		view = UserView();
		view.drawFunc = { |v|
			Pen.strokeColor = FxPalette.fade(colour, if (unit.enabled) { 0.8 } { 0.3 });
			Pen.width = 1;
			Pen.addRoundedRect(v.bounds.moveTo(0, 0).insetBy(0.5, 0.5), 5, 5);
			Pen.stroke;
		};
		view.layout = HLayout(*([face] ++ inner.collect(_.view) ++ [innerAdd])).margins_([3, 0, 3, 0]).spacing_(6);
		ctrl = SimpleController(unit)
			.put(\branches, { { this.prRebuild }.defer })
			.put(\rack, { { this.refresh }.defer })
			.put(\enabled, { { this.refresh }.defer });
		chainCtrl = SimpleController(shownChain).put(\units, { { this.prRebuild }.defer });
		strip.hints !? { |h| this.hints_(h) };
		this.refresh;
	}

	// the strip makes its cards again: which chain is shown, or what is in it, changed
	prRebuild { strip.prRebuild }

	prVisible { ^(list.bounds.height / rowHeight).floor.asInteger.max(1) }

	refresh {
		var late = unit.latency;
		if (view.isNil or: { view.isClosed }) { ^this };
		power.value = unit.enabled;
		liveSwitch.value = unit.live;
		infoText.string = if (late > 0) { "% ms".format((late * 1000).round(0.1)) } { "" };
		list.refresh;
		view.refresh;
		inner.do(_.refresh);
	}

	hints_ { |bar|
		hints = bar;
		bar.attach(power.view, "the rack in or out of the chain - out, the sound passes it by and it costs nothing");
		bar.attach(infoText, "how late the rack is: its latest chain that is lined up");
		bar.attach(liveSwitch.view, "live: no chain is held back - nothing added to the latency, the chains out of line");
		bar.attach(addBtn.view, "another chain beside these, empty: it passes the sound until something is put in it");
		bar.attach(openBtn.view, "the rack in a window: every chain with its effects");
		bar.attach(dropBtn.view, "take the rack out of the chain, with everything in it");
		bar.attach(innerAdd, { "add an effect to the chain %".format(unit.branches[unit.shown] !? (_.name) ? "") });
		bar.attach(list, { |v, x, y| this.prHint(x, y) });
		inner.do { |c| c.hints_(bar) };
	}

	remove {
		ctrl !? (_.remove);
		chainCtrl !? (_.remove);
		ctrl = nil;
		chainCtrl = nil;
		inner.do(_.remove);
	}

	// ---------------------------------------------------------- the list

	// where things are in a row, from its width: [name, summary, level, a, m, s] as [left, width]
	prZones {
		var w = list.bounds.width;
		^(name: [4, 56], summary: [64, w - 64 - 122], level: [w - 116, 50],
			align: [w - 58, 16], mute: [w - 38, 16], solo: [w - 18, 16])
	}

	prRowAt { |y|
		var i = (y / rowHeight).floor.asInteger + scroll;
		^if (i >= 0 and: { i < unit.branches.size }) { i }
	}

	prZoneAt { |x|
		var z = this.prZones;
		^#[\solo, \mute, \align, \level, \summary, \name].detect { |k| x >= (z[k][0] - 2) and: { x < (z[k][0] + z[k][1] + 2) } }
	}

	prDrawList {
		var z = this.prZones, w = list.bounds.width, anySolo = unit.anySolo, dimAll = unit.enabled.not;
		unit.branches.drop(scroll).keep(this.prVisible).do { |b, k|
			var i = k + scroll, y = k * rowHeight, picked = i == unit.shown;
			var heard = b.mute.not and: { anySolo.not or: { b.solo } };
			var a = if (dimAll) { 0.5 } { if (heard) { 1 } { 0.5 } };
			var box = { |key, on, colour, letter|
				var r = Rect(z[key][0], y + 3, z[key][1], rowHeight - 6);
				Pen.fillColor = if (on) { FxPalette.fade(colour, a) } { FxPalette.control };
				Pen.addRoundedRect(r, 3, 3);
				Pen.fill;
				Pen.stringCenteredIn(letter, r, FxPalette.smallFont, if (on) { FxPalette.bg } { FxPalette.dim });
			};
			if (picked) {
				Pen.fillColor = FxPalette.control;
				Pen.addRoundedRect(Rect(0, y + 1, w, rowHeight - 2), 3, 3);
				Pen.fill;
			};
			Pen.stringLeftJustIn(b.name, Rect(z[\name][0], y, z[\name][1], rowHeight), FxPalette.font,
				FxPalette.fade(if (picked) { FxPalette.text } { FxPalette.dim }, a));
			Pen.stringLeftJustIn(b.summary, Rect(z[\summary][0], y, z[\summary][1], rowHeight), FxPalette.smallFont,
				FxPalette.fade(FxPalette.dim, a));
			// the level: a bar
			Pen.fillColor = FxPalette.waveBg;
			Pen.fillRect(Rect(z[\level][0], y + (rowHeight / 2) - 2, z[\level][1], 4));
			Pen.fillColor = FxPalette.fade(FxPalette.wave, a);
			Pen.fillRect(Rect(z[\level][0], y + (rowHeight / 2) - 2, z[\level][1] * this.class.levelToBar(b.level), 4));
			box.(\align, b.align and: { unit.live.not }, FxPalette.wave, "a");
			box.(\mute, b.mute, FxPalette.accent, "m");
			box.(\solo, b.solo, Color(0.95, 0.85, 0.35), "s");
		};
		if (unit.branches.size > this.prVisible) {
			Pen.stringRightJustIn("% of %".format(scroll + 1, unit.branches.size),
				Rect(0, list.bounds.height - 10, w - 124, 10), FxPalette.smallFont, FxPalette.dim);
		};
	}

	prDown { |x, y, button, count|
		var i = this.prRowAt(y), b, zone = this.prZoneAt(x);
		if (i.isNil) { ^this };
		b = unit.branches[i];
		if (button == 1) { ^this.prMenu(b) };
		switch (zone,
			\solo, { b.solo = b.solo.not },
			\mute, { b.mute = b.mute.not },
			\align, { b.align = b.align.not },
			\level, {
				if (count == 2) { b.level = 0 } { dragging = b; this.prMove(x) };
			},
			{
				if (unit.shown != i) { unit.shown = i; this.prRebuild };
			}
		);
		list.refresh;
	}

	prMove { |x|
		var z = this.prZones;
		dragging !? { |b|
			b.level = this.class.barToLevel((x - z[\level][0]) / z[\level][1]).round(0.1);
			hints !? { |h| h.string = "chain %  level  %".format(b.name, this.prDb(b.level)) };
			list.refresh;
		};
	}

	prDb { |db| ^if (db <= -60) { "-inf dB" } { "% dB".format(db.round(0.1)) } }

	prMs { |seconds| ^"% ms".format((seconds * 1000).round(0.1)) }

	prHint { |x, y|
		var i = this.prRowAt(y), b, late;
		if (i.isNil) { ^"the rack's chains, side by side - click one to see its effects, right click to rename it or take it out" };
		b = unit.branches[i];
		late = b.latency;
		^switch (this.prZoneAt(x),
			\solo, { "solo %: only the soloed chains are heard".format(b.name) },
			\mute, { "mute %".format(b.name) },
			\align, {
				if (unit.live) { "align does nothing while the rack is live" } {
					if (b.align) {
						"% is lined up with the others: held back %".format(b.name, this.prMs(b.heldBack))
					} { "% is out of the line-up: not held back, and nothing waits for it".format(b.name) }
				}
			},
			\level, { "level of %: %  -  drag, double click for 0 dB".format(b.name, this.prDb(b.level)) },
			{
				"chain %: %  -  % late by itself, held back %  -  click to show its effects, right click for more"
					.format(b.name, b.summary, this.prMs(late), this.prMs(b.heldBack))
			}
		)
	}

	prMenu { |b|
		Menu(
			MenuAction("rename...", { this.class.renameDialog(b) }),
			MenuAction("take this chain out", { unit.removeBranch(b) }).enabled_(unit.branches.size > 1),
			MenuAction.separator,
			MenuAction("add a chain", { unit.shown = unit.addBranch.index; this.prRebuild })
		).front;
	}

	// a small window with the name in it: return keeps it
	*renameDialog { |branch|
		var w = Window("rename chain", Rect(300, 400, 240, 60), false);
		var field = TextField().string_(branch.name).font_(FxPalette.font)
			.background_(FxPalette.control).stringColor_(FxPalette.text);
		FxPalette.darken(w);
		field.action = { |f| if (f.string.notEmpty) { branch.name = f.string }; w.close };
		w.layout = VLayout(field).margins_(12);
		w.front;
		field.focus(true);
		^w
	}
}


FxRackPanel {
	var <unit, <view, rows, body, canvas, liveSwitch, infoText, addBtn, power;
	var ctrl, hints, running = false;

	asView { ^view }

	*new { |unit, parent| ^super.new.init(unit, parent) }

	init { |argUnit, parent|
		unit = argUnit;
		rows = [];
		power = FxPower().action_({ |p| unit.enabled = p.value });
		infoText = StaticText().font_(FxPalette.font).stringColor_(FxPalette.dim);
		liveSwitch = FxSwitch(["live"], true).action_({ |s| unit.live = s.value });
		addBtn = FxButton("+ chain", { unit.addBranch });
		body = ScrollView().hasBorder_(false).hasHorizontalScroller_(false).background_(FxPalette.bg);
		view = View(parent);
		view.layout = VLayout(
			HLayout(power.view,
				StaticText().string_("RACK").font_(FxPalette.boldFont).stringColor_(unit.class.identityColour),
				[infoText, stretch: 1], liveSwitch.view, addBtn.view).margins_(0).spacing_(8),
			[body, stretch: 1]
		).margins_(0).spacing_(8);
		ctrl = SimpleController(unit)
			.put(\branches, { { this.prRebuild }.defer })
			.put(\rack, { { this.refresh }.defer })
			.put(\enabled, { { this.refresh }.defer });
		this.prRebuild;
	}

	prRebuild {
		if (view.isNil or: { view.isClosed }) { ^this };
		rows.do { |r| r[\strip].remove };
		rows = unit.branches.collect { |b| this.prRow(b) };
		canvas = View().background_(FxPalette.bg);
		canvas.layout = VLayout(*(rows.collect { |r| r[\view] } ++ [nil])).margins_(0).spacing_(8);
		body.canvas = canvas;
		hints !? { this.hints_(hints) };
		rows.do { |r| r[\strip].running = running };
		this.refresh;
	}

	// one chain: what it is called and how loud at the left, its effects at the right
	prRow { |b|
		var name = TextField().string_(b.name).font_(FxPalette.font).fixedWidth_(96).fixedHeight_(22)
			.background_(FxPalette.control).stringColor_(FxPalette.text);
		var level = FxKnob("level", FxRackCard.levelSpec, \medium, false, FxPalette.wave);
		var align = FxSwitch(["align"], true).action_({ |s| b.align = s.value });
		var mute = FxSwitch(["mute"], true).action_({ |s| b.mute = s.value });
		var solo = FxSwitch(["solo"], true).action_({ |s| b.solo = s.value });
		var drop = FxButton("x", { unit.removeBranch(b) }, 20);
		var late = StaticText().font_(FxPalette.smallFont).stringColor_(FxPalette.dim);
		var strip = FxStrip(b.chain);
		var side = View().fixedWidth_(204).background_(FxPalette.card);
		name.action = { |f| if (f.string.notEmpty) { b.name = f.string } };
		level.default = 0;
		level.floorText = "-inf";
		level.action = { |k| b.level = k.mapped };
		side.layout = HLayout(
			VLayout(HLayout(name, drop.view).margins_(0).spacing_(4), late,
				HLayout(align.view, nil).margins_(0),
				HLayout(mute.view, solo.view, nil).margins_(0).spacing_(4), nil).margins_(0).spacing_(6),
			VLayout(level.view, nil).margins_(0)
		).margins_(8).spacing_(4);
		^(branch: b, strip: strip, name: name, level: level, align: align, mute: mute, solo: solo, late: late, drop: drop,
			view: View().layout_(HLayout(side, [strip.view, stretch: 1]).margins_(0).spacing_(8)))
	}

	refresh {
		var late = unit.latency;
		if (view.isNil or: { view.isClosed }) { ^this };
		power.value = unit.enabled;
		liveSwitch.value = unit.live;
		infoText.string = "% chain%  ·  % ms late%".format(unit.branches.size, if (unit.branches.size == 1) { "" } { "s" },
			(late * 1000).round(0.1), if (unit.live) { "  ·  live: nothing held back" } { "" });
		rows.do { |r|
			var b = r[\branch];
			if (r[\name].hasFocus.not) { r[\name].string = b.name };
			r[\level].mapped = b.level;
			r[\align].value = b.align;
			r[\align].inactive = unit.live;
			r[\mute].value = b.mute;
			r[\solo].value = b.solo;
			r[\late].string = "late %  ·  held %  ms".format((b.latency * 1000).round(0.1), (b.heldBack * 1000).round(0.1));
		};
	}

	hints_ { |bar|
		hints = bar;
		bar.attach(power.view, "the rack in or out of its chain");
		bar.attach(liveSwitch.view, "live: no chain is held back - nothing added to the latency, the chains out of line");
		bar.attach(addBtn.view, "another chain beside these, empty: it passes the sound until something is put in it");
		bar.attach(infoText, "how late the rack is: its latest chain that is lined up");
		rows.do { |r|
			var b = r[\branch];
			bar.attach(r[\name], "what this chain is called - type and press return");
			r[\level].hints = bar;
			bar.attach(r[\level].view, { "level of %  %".format(b.name, r[\level].text) });
			bar.attach(r[\align].view, "align: this chain is held back so it arrives with the latest one - off, it is not, and nothing waits for it");
			bar.attach(r[\mute].view, "mute this chain");
			bar.attach(r[\solo].view, "solo: only the soloed chains are heard");
			bar.attach(r[\drop].view, "take this chain out, with its effects");
			bar.attach(r[\late], "how late this chain is by itself, and how long the rack holds it back to line it up");
			r[\strip].hints = bar;
		};
	}

	running_ { |bool|
		running = bool == true;
		rows.do { |r| r[\strip].running = running };
	}

	remove {
		ctrl !? (_.remove);
		ctrl = nil;
		rows.do { |r| r[\strip].remove };
	}
}
