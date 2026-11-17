// =====================================================================
// FxWidgets - the controls every effect window is built from (sc-fx-ui)
//
// Drawn with Pen in FxPalette's colours, so they look the same in every
// window and on every platform, instead of Qt's stock widgets:
//
//   FxKnob     a value arc on a dim track, label above, value with its unit
//              below; big 56 / medium 40 / small 28. Drag up and down (shift:
//              fine), the wheel, double click: the default, click the value
//              to type one ("2k", "350ms", "e2"), right click: a menu.
//              `level` draws a live ring inside the track (a detector, say).
//   FxChoice   a menu button: the item, a chevron, a menu on click
//   FxSwitch   a row of lit segments (tabs, a choice of a few words) or one
//              lit toggle; with a label, a caption above like a knob's
//   FxPad      a big lit pad: a latch (click toggles; held past 0.35 s it is
//              momentary - freeze while held) or a trigger (go)
//   FxPower    the on/off button
//   FxButton   a flat text button
//   FxCard     a group: a lighter rounded card with a title line
//   FxMeter    in and out bars with a dB scale, held peaks, clip lights - and,
//              given `gain`, a gain-change bar between them
//   FxHeader   a device's top line: power, name, < presets >, save, ...
//
// Every one answers `view` (and `asView`, so it goes straight into a layout);
// `inactive` dims a control whose parameter does nothing at the moment,
// `muted` the whole panel of an effect that is off.
// =====================================================================

// What lets something outside - a controller mapper, a macro - work with every
// widget without this file knowing it. A panel tells a widget which parameter
// it is (mapUnitFunc: a function answering the unit, mapKey: one of the unit's
// *mapParams keys); a widget then draws a dot while `hasMap`, stands still
// while `locked` (drawn in lockColour: something else moves it), and asks here
// on a click:
//
//   FxMapHooks.addProvider({ |widget| [MenuAction("learn midi", { ... })] });
//   FxMapHooks.clickHook = { |widget| learning and: { arm(widget); true } };
//
// providers add items to the right-click menu; clickHook, answering true, takes
// a left click before the widget does (learn mode). `widgets` is every one that
// has a parameter and is still open, to repaint when the mappings change.
FxMapHooks {
	classvar <>clickHook, <providers, all;

	*initClass { providers = List.new; all = [] }

	*addProvider { |func| providers.add(func); ^func }
	*removeProvider { |func| providers.remove(func) }

	// an Array, kept by assignment: reject answers a new collection, and add
	// on that one would not reach a List held here
	*register { |widget| if (all.includes(widget).not) { all = all.add(widget) } }

	// ask from the GUI thread: it reads whether each view is still open
	*widgets {
		all = all.reject { |w| w.view.isNil or: { w.view.isClosed } };
		^all.copy
	}

	*items { |widget|
		if (widget.mapKey.isNil) { ^[] };
		^providers.collect { |f| f.value(widget) }.asArray.flatten.reject(_.isNil)
	}

	// a left click the hook wants: answers true, and the widget does nothing
	*intercept { |widget, button|
		^((button ? 0) == 0) and: { widget.mapKey.notNil } and: { clickHook.notNil }
			and: { clickHook.value(widget) == true }
	}

	// for a widget with no menu of its own: the hook, then the providers' menu
	// on a right click, then the lock. true: the click is spoken for
	*down { |widget, button|
		var items;
		if (this.intercept(widget, button)) { ^true };
		if ((button ? 0) == 1) {
			items = this.items(widget);
			if (items.notEmpty) { this.prFront(items); ^true };
		};
		^widget.locked
	}

	*prFront { |items| Menu(*items).front }

	// the dot that says a controller is on it
	*drawDot { |widget, x, y|
		if (widget.hasMap) {
			Pen.fillColor = FxPalette.sel;
			Pen.fillOval(Rect(x, y, 5, 5));
		};
	}
}


FxKnob {
	classvar <diameters;

	var <view, <spec, <value = 0, <>action, <>default, <label, <size, <bipolar, <colour;
	var <inactive = false, <muted = false, <tidal = false, <highlight = false;
	var <>box, <>dec, <>floorText, <>hint = "", <>hints, <>menuItems;
	var <level, <>levelMirror = false;
	var <stepNames;		// a knob over a list (a menu's items): it steps, shows ticks and the item
	var <>interval;		// what an arrow key moves it by - see FxPalette.nudge; nil: worked out from the spec
	var <keys = false;	// whether it can be selected and moved with the arrow keys: it can, unless told not
	var <selected = false;	// it has the keyboard: the arrow keys move this one
	var dragging = false, startY = 0, startV = 0, lastShift = false, editor;
	var <>mapUnitFunc, <mapKey, <hasMap = false, <locked = false, <>lockColour;	// see FxMapHooks

	asView { ^view }

	mapUnit { ^mapUnitFunc.value }
	mapKey_ { |key| mapKey = key; key !? { FxMapHooks.register(this) } }
	hasMap_ { |bool| bool = bool == true; if (hasMap != bool) { hasMap = bool; this.refresh } }
	locked_ { |bool| bool = bool == true; if (locked != bool) { locked = bool; this.refresh } }

	*initClass { diameters = (big: 56, medium: 40, small: 28) }

	*new { |label, spec, size = \medium, bipolar = false, colour, parent|
		^super.new.init(label, spec, size, bipolar, colour, parent)
	}

	*cellWidth { |size| ^if (size == \big) { 68 } { 56 } }
	*cellHeight { |size| ^16 + diameters[size ? \medium] + 18 }

	init { |argLabel, argSpec, argSize, argBipolar, argColour, parent|
		label = argLabel ? "";
		spec = argSpec.asSpec;
		size = if (diameters.includesKey(argSize)) { argSize } { \medium };
		bipolar = argBipolar ? false;
		colour = argColour ? FxPalette.wave;
		default = spec.default;
		view = UserView(parent).fixedSize_(Size(this.class.cellWidth(size), this.class.cellHeight(size)));
		view.drawFunc = { this.prDraw };
		view.mouseDownAction = { |v, x, y, mod, button, count| this.prDown(x, y, mod, button, count) };
		view.mouseMoveAction = { |v, x, y, mod| this.prMove(y, mod) };
		view.mouseUpAction = { this.prUp };
		view.mouseWheelAction = { |v, x, y, mod, xd, yd| this.prWheel(mod, yd) };
		this.keys_(true);
		^this
	}

	// --------------------------------------------------------- the keys

	// Selected by a click or by tab, the knob moves one interval with an arrow
	// key: up and right raise it, down and left lower it, shift a tenth as much.
	keys_ { |bool|
		keys = bool == true;
		view.canFocus = keys;
		if (keys) {
			view.keyDownAction = { |v, char, mod, uni, keycode, key| this.prKey(mod, key) };
			view.focusGainedAction = { selected = true; this.refresh; this.prSayHint };
			view.focusLostAction = { selected = false; this.refresh };
		} {
			view.keyDownAction = nil;
			selected = false;
		};
	}

	// one interval up (1) or down (-1)
	nudge { |dir = 1, fine = false|
		if (locked) { ^this };
		this.valueAction = if (stepNames.notNil) {
			value + (dir / (stepNames.size - 1).max(1))
		} {
			spec.unmap(FxPalette.nudge(this.mapped, dir, spec, interval, box, fine))
		};
		this.prSayHint;
	}

	// Qt's codes: left, up, right, down. Any other key goes on to the window
	prKey { |mod, key|
		var dir = switch (key, 16r01000013, 1, 16r01000014, 1, 16r01000015, -1, 16r01000012, -1);
		if (dir.isNil) { ^nil };
		this.nudge(dir, mod.isShift);
		^true
	}

	// ------------------------------------------------------------ value

	value_ { |v| value = v.clip(0, 1); this.refresh }
	valueAction_ { |v| this.value_(v); action.value(this) }
	mapped { ^spec.map(value) }
	mapped_ { |v| this.value_(spec.unmap(v)) }

	text {
		^if (stepNames.notNil) {
			stepNames.clipAt(this.mapped.round.asInteger).asString
		} {
			FxPalette.format(this.mapped, spec.units, box, dec, bipolar, floorText, spec)
		}
	}

	// make it a knob over these names: 0..n-1 in steps, a tick each
	stepNames_ { |names|
		stepNames = names;
		names !? { spec = ControlSpec(0, (names.size - 1).max(1), \lin, 1, spec.default.clip(0, names.size - 1)) };
		this.refresh;
	}

	label_ { |str| label = str ? ""; this.refresh }
	colour_ { |c| colour = c ? FxPalette.wave; this.refresh }
	inactive_ { |bool| if (inactive != bool) { inactive = bool; this.refresh } }
	muted_ { |bool| if (muted != bool) { muted = bool; this.refresh } }
	tidal_ { |bool| if (tidal != bool) { tidal = bool; this.refresh } }
	// the label in the knob's colour, while its handle in a display is hovered
	highlight_ { |bool| if (highlight != bool) { highlight = bool; this.refresh } }

	// a live value in the knob's own 0..1 (where a detector is now), or nil
	level_ { |v|
		var new = v !? { v.clip(0, 1) };
		if (new != level) { level = new; this.refresh };
	}

	refresh { if (view.notNil and: { view.isClosed.not }) { view.refresh } }

	// ------------------------------------------------------------- draw

	prAlpha { ^case { inactive } { 0.35 } { muted or: { locked } } { 0.55 } { true } { 1 } }

	prValueTop { ^16 + diameters[size] + 2 }

	prDraw {
		var w = view.bounds.width, d = diameters[size], r = d * 0.5, a = this.prAlpha;
		var c = (w * 0.5) @ (16 + r);
		var thick = switch (size, \big, 5, \small, 3, 4);
		var start = 0.75pi, sweep = 1.5pi, ang = start + (value * sweep), from, to, tip, la, over;
		var labelColour = case
			{ tidal } { Color(0.55, 0.85, 0.55) }
			{ highlight } { colour }
			{ true } { FxPalette.dim };
		var valueColour = if (dragging or: { editor.notNil }) { FxPalette.accent } { FxPalette.text };
		// selected: the one the arrow keys move
		if (selected) {
			Pen.strokeColor = FxPalette.fade(FxPalette.accent, 0.7);
			Pen.width = 1;
			Pen.addRoundedRect(Rect(0.5, 0.5, w - 1, view.bounds.height - 1), 4, 4);
			Pen.stroke;
			labelColour = FxPalette.text;
		};
		Pen.stringCenteredIn(label, Rect(0, 0, w, 14), FxPalette.font, FxPalette.fade(labelColour, a));
		// the track, then the value along it: from the bottom, or from the top if bipolar
		Pen.fillColor = FxPalette.fade(FxPalette.control, a);
		Pen.addAnnularWedge(c, r - thick, r, start, sweep);
		Pen.fill;
		// a stepped knob: a tick per step, just outside the track
		stepNames !? {
			var n = stepNames.size;
			Pen.strokeColor = FxPalette.fade(FxPalette.dim, 0.8 * a);
			Pen.width = 1;
			n.do { |i|
				var t = start + (i / (n - 1).max(1) * sweep), p = Point(cos(t), sin(t));
				Pen.line(c + (p * (r + 1)), c + (p * (r + 3)));
			};
			Pen.stroke;
		};
		from = if (bipolar) { 1.5pi } { start };
		to = ang;
		if ((to - from).abs > 0.001) {
			Pen.fillColor = FxPalette.fade(if (locked) { lockColour ? colour } { colour }, a);
			Pen.addAnnularWedge(c, r - thick, r, min(from, to), (to - from).abs);
			Pen.fill;
		};
		// the body and its pointer
		Pen.fillColor = FxPalette.fade(FxPalette.card, a);
		Pen.fillOval(Rect.aboutPoint(c, r - thick - 2, r - thick - 2));
		// the live ring: signal blue up to the knob's own value, its colour past it
		level !? {
			la = start + (level * sweep);
			over = (level > value) and: { levelMirror.not };
			Pen.fillColor = FxPalette.fade(FxPalette.wave, 0.9 * a);
			Pen.addAnnularWedge(c, r - thick - 4, r - thick - 2, start, (min(level, value) * sweep).max(0.001));
			Pen.fill;
			if (over) {
				Pen.fillColor = FxPalette.fade(colour, a);
				Pen.addAnnularWedge(c, r - thick - 4, r - thick - 2, ang, (la - ang).max(0.001));
				Pen.fill;
			} {
				if (level > value) {
					Pen.fillColor = FxPalette.fade(FxPalette.wave, 0.9 * a);
					Pen.addAnnularWedge(c, r - thick - 4, r - thick - 2, ang, (la - ang).max(0.001));
					Pen.fill;
				};
			};
		};
		tip = Point(cos(ang), sin(ang));
		Pen.strokeColor = FxPalette.fade(FxPalette.text, a);
		Pen.width = if (size == \small) { 1.5 } { 2 };
		Pen.line(c + (tip * (r * 0.22)), c + (tip * (r - thick - if (level.notNil) { 5 } { 3 })));
		Pen.stroke;
		if (editor.isNil) {
			Pen.stringCenteredIn(this.text, Rect(0, this.prValueTop, w, 15), FxPalette.font,
				FxPalette.fade(valueColour, a));
		};
		FxMapHooks.drawDot(this, c.x + r - 2, 16);
	}

	// ------------------------------------------------------------ mouse

	prDown { |x, y, mod, button, count|
		if (FxMapHooks.intercept(this, button)) { ^true };
		if (keys) { view.focus(true) };
		case
		{ (button ? 0) == 1 } { this.prMenu }
		{ locked } { nil }
		{ count == 2 } { this.valueAction = spec.unmap(default); this.prSayHint }
		{ y >= this.prValueTop } { this.prEdit }
		{ true } {
			dragging = true;
			startY = y;
			startV = value;
			lastShift = mod.isShift;
			this.refresh;
		};
		^true
	}

	prMove { |y, mod|
		var fine = mod.isShift;
		if (dragging.not or: { locked }) { ^true };
		// a change of speed mid drag carries on from where it is, no jump
		if (fine != lastShift) { startY = y; startV = value; lastShift = fine };
		this.valueAction = startV + ((startY - y) / 200 * if (fine) { 0.1 } { 1 });
		this.prSayHint;
		^true
	}

	prUp { dragging = false; this.refresh; ^true }

	prWheel { |mod, yd|
		if (yd != 0 and: { locked.not }) {
			this.valueAction = value + (yd.sign * case
				{ stepNames.notNil } { 1 / (stepNames.size - 1).max(1) }
				{ mod.isShift } { 0.002 }
				{ true } { 0.01 });
			this.prSayHint;
		};
		^true
	}

	prSayHint { hints !? { |h| h.string = "%  %   -   %".format(label, this.text, hint) } }

	prMenu {
		var actions = if (locked) { [] } { [
			MenuAction("back to " ++ FxPalette.format(default, spec.units, box, dec, bipolar, floorText, spec), {
				this.valueAction = spec.unmap(default)
			}),
			MenuAction("type a value...", { this.prEdit })
		] }, more = FxMapHooks.items(this);
		// more values worth one click: [[label, value], ...] in the parameter's own unit
		if (locked.not) {
			menuItems.do { |pair|
				actions = actions.add(MenuAction(pair[0].asString, { this.valueAction = spec.unmap(pair[1]) }));
			};
		};
		if (more.notEmpty) {
			if (actions.notEmpty) { actions = actions.add(MenuAction.separator) };
			actions = actions ++ more;
		};
		if (actions.notEmpty) { Menu(*actions).front };
	}

	// a field over the value: Return takes it, Escape or clicking away leaves it
	prEdit {
		var w = view.bounds.width;
		this.prCloseEditor;
		editor = TextField(view, Rect(2, this.prValueTop, w - 4, 15))
			.font_(FxPalette.font).align_(\center)
			.background_(FxPalette.waveBg).stringColor_(FxPalette.text)
			.string_(this.text);
		editor.action = { |t|
			var v = if (stepNames.notNil) {
				stepNames.collect(_.asString).indexOfEqual(t.string.stripWhiteSpace)
			} { FxPalette.parse(t.string, spec.units, box) };
			v !? { this.valueAction = spec.unmap(spec.constrain(v)) };
			{ this.prCloseEditor }.defer;
		};
		editor.keyDownAction = { |t, char, mod, uni, keycode, key|
			if (key == 16r01000000) { { this.prCloseEditor }.defer; true } { nil }
		};
		editor.focusLostAction = { { this.prCloseEditor }.defer };
		editor.focus(true);
		this.refresh;
	}

	prCloseEditor {
		var e = editor;
		editor = nil;
		e !? { if (e.isClosed.not) { e.remove } };
		this.refresh;
	}
}


FxChoice {
	var <view, <items, <value = 0, <>action, <label, <width;
	var <inactive = false, <muted = false, <tidal = false, <>override;
	var <>hint = "", <>hints;
	var <>mapUnitFunc, <mapKey, <hasMap = false, <locked = false, <>lockColour;	// see FxMapHooks

	asView { ^view }

	mapUnit { ^mapUnitFunc.value }
	mapKey_ { |key| mapKey = key; key !? { FxMapHooks.register(this) } }
	hasMap_ { |bool| bool = bool == true; if (hasMap != bool) { hasMap = bool; this.refresh } }
	locked_ { |bool| bool = bool == true; if (locked != bool) { locked = bool; this.refresh } }

	*new { |label, items, width, parent| ^super.new.init(label, items, width, parent) }

	// wide enough for its longest item and the chevron
	*widthFor { |items|
		var w = items.collect { |s| s.asString.bounds(FxPalette.font).width }.maxItem ? 40;
		^(w + 30).ceil.clip(56, 140)
	}

	init { |argLabel, argItems, argWidth, parent|
		label = argLabel;
		items = (argItems ? []).collect(_.asString);
		width = argWidth ?? { this.class.widthFor(items) };
		view = UserView(parent).fixedSize_(Size(width, this.prBoxTop + 22));
		view.drawFunc = { this.prDraw };
		view.mouseDownAction = { |v, x, y, mod, button|
			if (FxMapHooks.down(this, button).not) { this.prDown(y) };
			true
		};
		view.mouseWheelAction = { |v, x, y, mod, xd, yd|
			if (yd != 0 and: { items.notEmpty } and: { locked.not }) {
				this.valueAction = (value - yd.sign).clip(0, items.size - 1)
			};
			true
		};
		^this
	}

	prBoxTop { ^if (label.notNil) { 16 } { 0 } }

	items_ { |array| items = array.collect(_.asString); value = value.clip(0, (items.size - 1).max(0)); this.refresh }
	value_ { |i| value = (i ? 0).asInteger; this.refresh }
	valueAction_ { |i| this.value_(i); action.value(this) }
	item { ^items[value] }
	label_ { |str| label = str; this.refresh }
	inactive_ { |bool| if (inactive != bool) { inactive = bool; this.refresh } }
	muted_ { |bool| if (muted != bool) { muted = bool; this.refresh } }
	tidal_ { |bool| if (tidal != bool) { tidal = bool; this.refresh } }

	refresh { if (view.notNil and: { view.isClosed.not }) { view.refresh } }

	prDraw {
		var w = view.bounds.width, top = this.prBoxTop, r = Rect(0, top, w, 22);
		var a = case { inactive } { 0.35 } { muted } { 0.55 } { true } { 1 };
		var cx = w - 12, cy = top + 11;
		label !? {
			Pen.stringCenteredIn(label, Rect(0, 0, w, 14), FxPalette.font,
				FxPalette.fade(if (tidal) { Color(0.55, 0.85, 0.55) } { FxPalette.dim }, a));
		};
		Pen.fillColor = FxPalette.fade(FxPalette.control, a);
		Pen.addRoundedRect(r, 3, 3);
		Pen.fill;
		Pen.stringLeftJustIn(override ? items[value] ? "", r.insetAll(7, 0, 22, 0), FxPalette.font,
			FxPalette.fade(FxPalette.text, a));
		Pen.fillColor = FxPalette.fade(FxPalette.dim, a);
		Pen.moveTo((cx - 4) @ (cy - 2));
		Pen.lineTo((cx + 4) @ (cy - 2));
		Pen.lineTo(cx @ (cy + 3));
		Pen.lineTo((cx - 4) @ (cy - 2));
		Pen.fill;
		FxMapHooks.drawDot(this, w - 7, top + 2);
	}

	prDown { |y|
		if (items.isEmpty) { ^this };
		Menu(*items.collect { |name, i|
			MenuAction(name, { this.valueAction = i }).checkable_(true).checked_(i == value)
		}).front;
	}
}


// segments side by side, one lit (tabs, a choice of a few words) - or, made
// with toggle: true, one segment lit while on. Given a label, a caption above,
// on a knob's label line, so a segmented choice sits in a row of knobs.
FxSwitch {
	var <view, <items, <value, <>action, <toggle, <>colour, <muted = false, <inactive = false;
	var <label, <tidal = false, <inactiveItems, <itemColours, <hot, <marks;
	var widths, top = 0;
	var <>mapUnitFunc, <mapKey, <hasMap = false, <locked = false, <>lockColour;	// see FxMapHooks

	asView { ^view }

	mapUnit { ^mapUnitFunc.value }
	mapKey_ { |key| mapKey = key; key !? { FxMapHooks.register(this) } }
	hasMap_ { |bool| bool = bool == true; if (hasMap != bool) { hasMap = bool; this.refresh } }
	locked_ { |bool| bool = bool == true; if (locked != bool) { locked = bool; this.refresh } }

	*new { |items, toggle = false, parent, label| ^super.new.init(items, toggle, parent, label) }

	init { |argItems, argToggle, parent, argLabel|
		var w;
		items = argItems.collect(_.asString);
		toggle = argToggle;
		label = argLabel;
		value = if (toggle) { false } { 0 };
		colour = FxPalette.accent;
		inactiveItems = [];
		marks = [];
		widths = items.collect { |s| (s.bounds(FxPalette.font).width + 18).ceil };
		top = if (label.notNil) { 16 } { 0 };
		w = widths.sum;
		label !? { w = max(w, (label.asString.bounds(FxPalette.font).width + 8).ceil) };
		view = UserView(parent).fixedSize_(Size(w, top + 20));
		view.drawFunc = { this.prDraw };
		view.mouseDownAction = { |v, x, y, mod, button|
			if (FxMapHooks.down(this, button).not) { this.prDown(x) };
			true
		};
		^this
	}

	value_ { |v| value = v; this.refresh }
	valueAction_ { |v| this.value_(v); action.value(this) }
	muted_ { |bool| if (muted != bool) { muted = bool; this.refresh } }
	inactive_ { |bool| if (inactive != bool) { inactive = bool; this.refresh } }
	tidal_ { |bool| if (tidal != bool) { tidal = bool; this.refresh } }
	label_ { |str| if (top > 0) { label = str; this.refresh } }
	inactiveItems_ { |list| inactiveItems = list ? []; this.refresh }
	// an unlit segment's text in its own colour (an eq's band numbers)
	itemColours_ { |list| itemColours = list; this.refresh }
	// a ring round one segment: its handle is hovered in a display
	hot_ { |i| if (hot != i) { hot = i; this.refresh } }
	// a small accent dot on these segments: a page holding something not plain
	marks_ { |list| marks = list ? []; this.refresh }

	refresh { if (view.notNil and: { view.isClosed.not }) { view.refresh } }

	prOn { |i| ^if (toggle) { value == true } { value == i } }

	prDraw {
		var x = 0, a = case { inactive } { 0.35 } { muted } { 0.55 } { true } { 1 };
		label !? {
			Pen.stringCenteredIn(label, Rect(0, 0, view.bounds.width, 14), FxPalette.font,
				FxPalette.fade(if (tidal) { Color(0.55, 0.85, 0.55) } { FxPalette.dim }, a));
		};
		items.do { |name, i|
			var r = Rect(x, top, widths[i], 20), on = this.prOn(i);
			var dim = inactiveItems.includes(i);
			var own = itemColours !? { itemColours[i] };
			Pen.fillColor = FxPalette.fade(if (on) { colour } { FxPalette.control }, a);
			Pen.addRoundedRect(r.insetBy(0.5, 0), 3, 3);
			Pen.fill;
			Pen.stringCenteredIn(name, r, FxPalette.font, FxPalette.fade(
				case
				{ on } { FxPalette.bg }
				{ dim } { FxPalette.waveDim }
				{ own.notNil } { own }
				{ true } { FxPalette.text }, a));
			if (hot == i) {
				Pen.strokeColor = FxPalette.fade(own ? FxPalette.text, a);
				Pen.width = 1;
				Pen.addRoundedRect(r.insetBy(1.5, 1), 3, 3);
				Pen.stroke;
			};
			if (marks.includes(i)) {
				Pen.fillColor = FxPalette.fade(if (on) { FxPalette.bg } { FxPalette.accent }, a);
				Pen.fillOval(Rect(r.right - 8, r.top + 3, 5, 5));
			};
			x = x + widths[i];
		};
		// thin dividers between segments that are not lit
		Pen.strokeColor = FxPalette.fade(FxPalette.bg, a);
		Pen.width = 1;
		x = 0;
		(items.size - 1).do { |i|
			x = x + widths[i];
			Pen.line(x @ (top + 3), x @ (top + 17));
		};
		Pen.stroke;
		FxMapHooks.drawDot(this, widths.sum - 6, top + 1);
	}

	prDown { |x|
		var edge = 0, hit;
		if (toggle) { this.valueAction_(value.not); ^this };
		widths.do { |w, i| if (hit.isNil and: { x < (edge + w) }) { hit = i }; edge = edge + w };
		hit !? { if (hit != value) { this.valueAction = hit } };
	}
}


// a big lit pad for the act that decides an effect: freeze, hold, go.
// \latch: a click toggles it; held past 0.35 s it was momentary, and letting
// go puts it back (freeze while you hold it). \trigger: the action on press,
// lit while pressed. The label sits in the pad; the line above is a caption,
// like a knob's, so a pad stands in a row of knobs.
FxPad {
	var <view, <label, <value = false, <>action, <mode, <>colour, <>outline, <caption;
	var <inactive = false, <muted = false, <tidal = false, <>hint = "", <>hints;
	var down = false, downAt = 0, flipped = false;
	var <>mapUnitFunc, <mapKey, <hasMap = false, <locked = false, <>lockColour;	// see FxMapHooks

	asView { ^view }

	mapUnit { ^mapUnitFunc.value }
	mapKey_ { |key| mapKey = key; key !? { FxMapHooks.register(this) } }
	hasMap_ { |bool| bool = bool == true; if (hasMap != bool) { hasMap = bool; this.refresh } }
	locked_ { |bool| bool = bool == true; if (locked != bool) { locked = bool; this.refresh } }

	*new { |label, mode = \latch, width = 68, height = 90, parent, caption|
		^super.new.init(label, mode, width, height, parent, caption)
	}

	init { |argLabel, argMode, width, height, parent, argCaption|
		label = argLabel ? "";
		mode = argMode ? \latch;
		caption = argCaption;
		colour = FxPalette.accent;
		view = UserView(parent).fixedSize_(Size(width ? 68, height ? 90));
		view.drawFunc = { this.prDraw };
		view.mouseDownAction = { |v, x, y, mod, button|
			if (FxMapHooks.down(this, button).not) { this.prDown(button) };
			true
		};
		view.mouseUpAction = { this.prUp; true };
		^this
	}

	value_ { |bool| value = bool == true; this.refresh }
	valueAction_ { |bool| this.value_(bool); action.value(this) }
	label_ { |str| label = str ? ""; this.refresh }
	caption_ { |str| caption = str; this.refresh }
	inactive_ { |bool| if (inactive != bool) { inactive = bool; this.refresh } }
	muted_ { |bool| if (muted != bool) { muted = bool; this.refresh } }
	tidal_ { |bool| if (tidal != bool) { tidal = bool; this.refresh } }

	refresh { if (view.notNil and: { view.isClosed.not }) { view.refresh } }

	prLit { ^if (mode == \trigger) { down } { value } }

	prDraw {
		var b = view.bounds, w = b.width, h = b.height, a, lit = this.prLit;
		var r = Rect(2, 16, w - 4, h - 16 - 4);
		a = case { inactive } { 0.35 } { muted } { 0.55 } { true } { 1 };
		caption !? {
			Pen.stringCenteredIn(caption, Rect(0, 0, w, 14), FxPalette.font,
				FxPalette.fade(if (tidal) { Color(0.55, 0.85, 0.55) } { FxPalette.dim }, a));
		};
		Pen.fillColor = FxPalette.fade(if (lit) { colour } { FxPalette.control }, a);
		Pen.addRoundedRect(r, 6, 6);
		Pen.fill;
		if (lit.not) {
			// unlit, the outline wears the family's colour: lit is always the accent, on
			Pen.strokeColor = FxPalette.fade(outline ? colour, 0.55 * a);
			Pen.width = 1.5;
			Pen.addRoundedRect(r.insetBy(0.75, 0.75), 6, 6);
			Pen.stroke;
		};
		Pen.stringCenteredIn(label, r, FxPalette.boldFont,
			FxPalette.fade(if (lit) { FxPalette.bg } { FxPalette.text }, a));
		FxMapHooks.drawDot(this, w - 12, 20);
	}

	prDown { |button|
		if ((button ? 0) != 0) { ^this };
		down = true;
		downAt = Main.elapsedTime;
		if (mode == \trigger) {
			action.value(this);
		} {
			value = value.not;
			flipped = true;
			action.value(this);
		};
		this.refresh;
	}

	prUp {
		// held: it was momentary, so letting go puts it back
		if ((mode == \latch) and: { flipped } and: { (Main.elapsedTime - downAt) > 0.35 }) {
			value = value.not;
			action.value(this);
		};
		down = false;
		flipped = false;
		this.refresh;
	}
}


FxPower {
	var <view, <value = false, <>action;

	asView { ^view }

	*new { |parent| ^super.new.init(parent) }

	init { |parent|
		view = UserView(parent).fixedSize_(Size(22, 22));
		view.drawFunc = { this.prDraw };
		view.mouseDownAction = { this.valueAction = value.not; true };
		^this
	}

	value_ { |bool| value = bool == true; this.refresh }
	valueAction_ { |bool| this.value_(bool); action.value(this) }
	refresh { if (view.notNil and: { view.isClosed.not }) { view.refresh } }

	prDraw {
		var c = 11 @ 11, col = if (value) { FxPalette.accent } { FxPalette.dim };
		Pen.fillColor = if (value) { FxPalette.fade(FxPalette.accent, 0.18) } { FxPalette.control };
		Pen.fillOval(Rect(0, 0, 22, 22));
		Pen.strokeColor = col;
		Pen.width = 1.8;
		Pen.addArc(c, 6, -0.5pi + 0.6, 2pi - 1.2);
		Pen.stroke;
		Pen.line(11 @ 3.5, 11 @ 10);
		Pen.stroke;
	}
}


FxButton {
	var <view, <string, <>action, <enabled = true, down = false;
	var <inactive = false, <muted = false;
	var <>mapUnitFunc, <mapKey, <hasMap = false, <locked = false, <>lockColour;	// see FxMapHooks

	asView { ^view }

	mapUnit { ^mapUnitFunc.value }
	mapKey_ { |key| mapKey = key; key !? { FxMapHooks.register(this) } }
	hasMap_ { |bool| bool = bool == true; if (hasMap != bool) { hasMap = bool; this.refresh } }
	locked_ { |bool| bool = bool == true; if (locked != bool) { locked = bool; this.refresh } }

	*new { |string, action, width, parent| ^super.new.init(string, action, width, parent) }

	init { |argString, argAction, width, parent|
		string = argString;
		action = argAction;
		view = UserView(parent).fixedSize_(Size(width ?? { (string.bounds(FxPalette.font).width + 20).ceil }, 22));
		view.drawFunc = { this.prDraw };
		view.mouseDownAction = { |v, x, y, mod, button|
			if (FxMapHooks.down(this, button).not and: { enabled }) { down = true; this.refresh };
			true
		};
		view.mouseUpAction = { |v, x, y|
			var was = down;
			down = false;
			this.refresh;
			if (was and: { v.bounds.moveTo(0, 0).containsPoint(x @ y) }) { action.value(this) };
			true
		};
		^this
	}

	enabled_ { |bool| enabled = bool; this.refresh }
	string_ { |str| string = str; this.refresh }
	inactive_ { |bool| if (inactive != bool) { inactive = bool; this.refresh } }
	muted_ { |bool| if (muted != bool) { muted = bool; this.refresh } }
	refresh { if (view.notNil and: { view.isClosed.not }) { view.refresh } }

	prDraw {
		var r = view.bounds.moveTo(0, 0);
		var a = case { enabled.not or: { inactive } } { 0.35 } { muted } { 0.55 } { true } { 1 };
		Pen.fillColor = FxPalette.fade(if (down) { FxPalette.controlHi } { FxPalette.control }, a);
		Pen.addRoundedRect(r, 3, 3);
		Pen.fill;
		Pen.stringCenteredIn(string, r, FxPalette.font, FxPalette.fade(FxPalette.text, a));
		FxMapHooks.drawDot(this, r.width - 7, 2);
	}
}


// a group of controls: a lighter card, its title (in its colour, if it has
// one) and anything else on the title line - a selector, say - at the right
FxCard {
	var <view, <titleView, <colour;

	asView { ^view }

	*new { |title, content, colour, extras| ^super.new.init(title, content, colour, extras) }

	init { |title, content, argColour, extras|
		colour = argColour;
		titleView = StaticText().string_(title ? "").font_(FxPalette.smallFont)
			.stringColor_(colour ? FxPalette.dim).fixedHeight_(20);
		view = UserView();
		view.drawFunc = { |v|
			Pen.fillColor = FxPalette.card;
			Pen.addRoundedRect(v.bounds.moveTo(0, 0), 4, 4);
			Pen.fill;
			colour !? {
				Pen.fillColor = colour;
				Pen.addRoundedRect(Rect(0, 0, 3, v.bounds.height), 1.5, 1.5);
				Pen.fill;
			};
		};
		view.layout = VLayout(
			HLayout(*([titleView, nil] ++ (extras ? []))).margins_(0).spacing_(6),
			content,
			nil		// cards side by side keep their labels on one line
		).margins_([8, 6, 8, 8]).spacing_(4);
		^this
	}

	title_ { |str| titleView.string = str }
	colour_ { |c| colour = c; titleView.stringColor = c ? FxPalette.dim; view.refresh }
}


// in and out, on a dB scale, with the peak held as a number and a clip light
// that stays until the meter is clicked. `gain_ { dB or nil }` adds a bar
// between them for the gain change (a compressor): a cut hangs from 0 dB, a
// lift rises from the floor, on the same scale. *dbToY and *ticks are the
// scale, so a display can draw its grid on the meter's lines.
FxMeter {
	var <view, <>unit, holdIn = 0, holdOut = 0, clipIn = false, clipOut = false;
	var <gainFunc, <>gainColour, holdGain = 0;

	asView { ^view }

	*new { |unit| ^super.new.init(unit) }

	*ticks { ^#[0, -6, -12, -24, -48] }
	*topInset { ^24 }
	*bottomInset { ^16 }
	*dbToY { |db, h| ^db.linlin(-60, 6, h - this.bottomInset, this.topInset, \minmax) }

	init { |argUnit|
		unit = argUnit;
		gainColour = FxPalette.gain;
		view = UserView().fixedWidth_(64);
		view.drawFunc = { |v| this.prDraw(v) };
		view.mouseDownAction = {
			holdIn = 0; holdOut = 0; holdGain = 0; clipIn = false; clipOut = false;
			this.refresh; true
		};
		^this
	}

	gain_ { |func|
		gainFunc = func;
		view.fixedWidth = if (func.notNil) { 84 } { 64 };
		this.refresh;
	}

	refresh { if (view.notNil and: { view.isClosed.not }) { view.refresh } }

	prDraw { |v|
		var h = v.bounds.height, w = v.bounds.width, top = this.class.topInset, bottom = h - this.class.bottomInset;
		var toY = { |db| this.class.dbToY(db, h) };
		var inL = unit !? { unit.tryPerform(\inLevel) } ? 0, outL = unit !? { unit.tryPerform(\outLevel) } ? 0;
		var g = gainFunc !? { gainFunc.value }, bar, outX;
		holdIn = max(holdIn * 0.995, inL);
		holdOut = max(holdOut * 0.995, outL);
		g !? { if (g.abs > holdGain.abs) { holdGain = g } { holdGain = holdGain * 0.995 } };
		if (inL > 1) { clipIn = true };
		if (outL > 1) { clipOut = true };
		Pen.fillColor = FxPalette.waveBg;
		Pen.addRoundedRect(v.bounds.moveTo(0, 0), 4, 4);
		Pen.fill;
		// the scale
		this.class.ticks.do { |db|
			var y = toY.(db);
			Pen.stringRightJustIn(db.asString, Rect(0, y - 6, 20, 12), FxPalette.smallFont, FxPalette.dim);
			Pen.fillColor = FxPalette.fade(FxPalette.waveDim, 0.6);
			Pen.fillRect(Rect(23, y, w - 26, 1));
		};
		bar = { |x, level, hold, clip, colour, name|
			var db = level.max(1e-5).ampdb, y = toY.(db), zero = toY.(0);
			Pen.fillColor = FxPalette.card;
			Pen.fillRect(Rect(x, top, 14, bottom - top));
			Pen.fillColor = colour;
			Pen.fillRect(Rect(x, y.max(zero), 14, bottom - y.max(zero)));
			if (y < zero) {
				Pen.fillColor = FxPalette.clip;
				Pen.fillRect(Rect(x, y, 14, zero - y));
			};
			Pen.fillColor = FxPalette.peak;
			Pen.fillRect(Rect(x, toY.(hold.max(1e-5).ampdb), 14, 1));
			Pen.fillColor = if (clip) { FxPalette.clip } { FxPalette.control };
			Pen.fillRect(Rect(x, 15, 14, 5));
			Pen.stringCenteredIn(if (hold < 1e-3) { "-inf" } { hold.ampdb.round(1).asInteger.asString },
				Rect(x - 5, 1, 24, 12), FxPalette.smallFont, FxPalette.text);
			Pen.stringCenteredIn(name, Rect(x - 5, bottom + 2, 24, 12), FxPalette.smallFont, FxPalette.dim);
		};
		bar.(25, inL, holdIn, clipIn, FxPalette.waveDim, "in");
		outX = 44;
		gainFunc !? {
			var x = 44, zero = toY.(0), floor = toY.(-60), gv = g ? 0, y;
			Pen.fillColor = FxPalette.card;
			Pen.fillRect(Rect(x, top, 14, bottom - top));
			Pen.fillColor = gainColour;
			if (gv < 0) {
				y = toY.(gv);
				Pen.fillRect(Rect(x, zero, 14, (y - zero).max(0)));
			} {
				y = toY.(-60 + gv);
				Pen.fillRect(Rect(x, y, 14, (floor - y).max(0)));
			};
			Pen.stringCenteredIn(if (holdGain.abs < 0.05) { "0" } { FxPalette.fixed(holdGain, if (holdGain.abs < 10) { 1 } { 0 }) },
				Rect(x - 6, 1, 26, 12), FxPalette.smallFont, gainColour);
			Pen.stringCenteredIn("gain", Rect(x - 6, bottom + 2, 26, 12), FxPalette.smallFont, FxPalette.dim);
			outX = 63;
		};
		bar.(outX, outL, holdOut, clipOut, FxPalette.wave, "out");
	}
}


// A device's top line, shared by every effect window: power, the name (in the
// effect's identity colour, if it has one), < preset menu > (the name with a *
// once something has moved), save (a name dialog), ... (save as, delete, copy,
// paste, back to the defaults, hints), then any extras at the right.
// It speaks the preset protocol of FxParamUnit, ParaEQ and Compressor
// (presetNames, presetName, preset_, saveAsPreset, removePreset, isUserPreset,
// hasClipboard); copy, paste and the defaults are functions, worked out from
// what the unit answers and replaceable.
//
//   header = FxHeader(unit, "eq", FxPalette.ivory, [spectrumSwitch.view]);
//   header.hints = bar;  header.refresh;  header.unit = other;
FxHeader {
	var <view, <unit, <cls, <title, <colour;
	var <power, <titleText, prevBtn, nextBtn, <presetChoice, saveBtn, moreBtn;
	var lastPreset, hints;
	var <>copyFunc, <>pasteFunc, <>defaultsFunc, <>moreItems;

	asView { ^view }

	*new { |unit, title, colour, extras, showPower = true|
		^super.new.init(unit, title, colour, extras, showPower)
	}

	init { |argUnit, argTitle, argColour, extras, showPower|
		unit = argUnit;
		cls = unit !? (_.class);
		title = (argTitle ?? { cls !? { cls.tryPerform(\title) ?? { cls.name.asString.toLower } } } ? "").asString;
		colour = argColour;
		lastPreset = unit !? { unit.tryPerform(\presetName) };
		copyFunc = { |u| if (u.respondsTo(\copySettings)) { u.copySettings } { u.tryPerform(\copyBands) } };
		pasteFunc = { |u| if (u.respondsTo(\pasteSettings)) { u.pasteSettings } { u.tryPerform(\pasteBands) } };
		defaultsFunc = { |u|
			case
			{ u.class.respondsTo(\fullSettings) } { u.settings_(u.class.fullSettings(())) }
			{ u.respondsTo(\flat) } { u.flat }
		};
		power = FxPower().action_({ |p| unit !? (_.enabled_(p.value)) });
		power.view.visible = showPower != false;
		titleText = StaticText().string_(title.toUpper).font_(FxPalette.boldFont)
			.stringColor_(colour ? FxPalette.text);
		prevBtn = FxButton("<", { this.prStep(-1) }, 22);
		nextBtn = FxButton(">", { this.prStep(1) }, 22);
		presetChoice = FxChoice(nil, [], 170).action_({ |c|
			var name = cls !? { cls.presetNames[c.value] };
			name !? { lastPreset = name; unit !? (_.preset_(name)) };
		});
		saveBtn = FxButton("save", { this.prSaveDialog });
		moreBtn = FxButton("...", { this.prMoreMenu }, 30);
		view = View().layout_(HLayout(*([
			power.view, titleText, View().fixedWidth_(12),
			prevBtn.view, presetChoice.view, nextBtn.view, saveBtn.view, moreBtn.view,
			nil
		] ++ (extras ? []))).spacing_(6).margins_(0));
		this.refresh;
		^this
	}

	unit_ { |u|
		unit = u;
		cls = u !? (_.class);
		lastPreset = u !? { u.tryPerform(\presetName) };
		this.refresh;
	}

	title_ { |str| title = str.asString; titleText.string = title.toUpper }
	colour_ { |c| colour = c; titleText.stringColor = c ? FxPalette.text }

	isClosed { ^view.isNil or: { view.isClosed } }

	refresh {
		if (this.isClosed) { ^this };
		power.value = unit !? { unit.tryPerform(\enabled) } ? false;
		this.prRefreshPreset;
	}

	prNames { ^cls !? { cls.tryPerform(\presetNames) } ? [] }

	// the name shown, with a * once something has moved since it was chosen
	prRefreshPreset {
		var names = this.prNames, pre;
		presetChoice.items = names.collect(_.asString);
		pre = unit !? { unit.tryPerform(\presetName) };
		if (pre.notNil) {
			lastPreset = pre;
			presetChoice.value = names.indexOf(pre) ? 0;
			presetChoice.override = nil;
		} {
			presetChoice.value = (lastPreset !? { names.indexOf(lastPreset) }) ? 0;
			presetChoice.override = (lastPreset ? "custom").asString ++ " *";
		};
		presetChoice.refresh;
	}

	prStep { |delta|
		var names = this.prNames, i, name;
		if (names.isEmpty or: { unit.isNil }) { ^this };
		i = names.indexOf(unit.tryPerform(\presetName) ? lastPreset);
		i = if (i.isNil) { if (delta > 0) { 0 } { names.size - 1 } } { (i + delta).wrap(0, names.size - 1) };
		name = names[i];
		lastPreset = name;
		unit.preset_(name);
	}

	prMoreMenu {
		var pre = unit !? { unit.tryPerform(\presetName) };
		var actions = [
			MenuAction("save as...", { this.prSaveDialog }),
			MenuAction("delete " ++ (pre ? "preset"), { pre !? { cls.removePreset(pre) } })
				.enabled_(pre.notNil and: { cls.tryPerform(\isUserPreset, pre) == true }),
			MenuAction("copy settings", { unit !? { |u| copyFunc.value(u) } }),
			MenuAction("paste settings", { unit !? { |u| pasteFunc.value(u) } })
				.enabled_(cls.tryPerform(\hasClipboard) == true),
			MenuAction("back to the defaults", { unit !? { |u| defaultsFunc.value(u) } })
		];
		moreItems.do { |pair| actions = actions.add(MenuAction(pair[0].asString, { pair[1].value(unit) })) };
		hints !? { |h|
			actions = actions.add(MenuAction("hints", { h.text.visible = h.text.visible.not })
				.checkable_(true).checked_(h.text.visible));
		};
		Menu(*actions).front;
	}

	prSaveDialog {
		var w, field, ok, save;
		var start = if (lastPreset.notNil and: { cls.tryPerform(\isUserPreset, lastPreset) == true }) { lastPreset.asString } { "" };
		w = Window("save a % preset".format(title), Rect(300, 400, 320, 76), resizable: false);
		FxPalette.darken(w);
		field = TextField().font_(FxPalette.font).string_(start);
		save = {
			var name = field.string.stripWhiteSpace;
			if (name.notEmpty and: { unit.notNil }) {
				if (unit.saveAsPreset(name).notNil) { lastPreset = unit.tryPerform(\presetName) ? name };
				w.close;
			};
		};
		ok = FxButton("save", save, 52);
		field.action = save;
		w.layout = VLayout(
			StaticText().string_("a name for these settings - in this session and the next")
				.font_(FxPalette.smallFont).stringColor_(FxPalette.dim),
			HLayout(field, ok.view).spacing_(6)
		).margins_(8).spacing_(6);
		w.front;
		field.focus(true);
	}

	hints_ { |bar|
		hints = bar;
		if (bar.isNil) { ^this };
		bar.attach(power.view, "switch it on - off, it costs nothing, or the tail rings out and then nothing");
		bar.attach(presetChoice.view, "ready made settings, then any you have saved - a * means you have changed it since");
		bar.attach([prevBtn.view, nextBtn.view], "the preset before or after this one");
		bar.attach(saveBtn.view, "keep these settings under a name - in this session and the next");
		bar.attach(moreBtn.view, "save as, delete, copy, paste, back to the defaults, hints");
	}

	hints { ^hints }
}
