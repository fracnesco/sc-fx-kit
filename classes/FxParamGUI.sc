// =====================================================================
// FxParamPanel - an FxParamUnit's controls as one view; FxParamGUI - as a window
//
//   [x] on  preset [warm v] [name    ][save][delete] [copy][paste]     (extras)
//   +--------------------------------------------------------+ +----+
//   |  the effect's own display                              | |bars|
//   +--------------------------------------------------------+ +----+
//   section   label  label  label  [x] toggle   label
//              (o)    (o)    (o)                [menu v]
//             [1.0]  [2.0]  [100]
//
// One row per section of the unit's table, so a new parameter turns up by
// being written there. It is what every window showing such an effect is built
// from: its own, and a chain's (FxChainPanel).
//
//   panel = FxParamPanel(unit);
//   window.layout = VLayout(panel.view, hints.view);
//   panel.hints = hints;
//   panel.running = true;         // meters and the display, while on screen
//   panel.remove;                 // in window.onClose
// =====================================================================

FxParamPanel {
	var <unit, <cls, <view, <display, <bars;
	var onBox, tidalBox, presetMenu, nameField, saveBtn, delBtn, copyBtn, pasteBtn;
	var widgets, sectionLabels, ctrl, classCtrl, hints, running = false;
	var tidalShown;			// what the labels show now: [name, keys], so a refresh at 10 Hz changes nothing

	*new { |unit, parent| ^super.new.init(unit, parent) }

	init { |argUnit, parent|
		var rows;
		unit = argUnit;
		cls = unit.class;
		widgets = IdentityDictionary.new;
		sectionLabels = [];
		bars = FxLevelBars(unit);
		display = cls.displayClass !? { |d| d.new(unit) };
		rows = [this.prTopRow];
		rows = rows.add([HLayout(*(display !? { [display.view] } ? [nil]) ++ [bars.view]).spacing_(4), \stretch, 1]);
		cls.sections.do { |section| rows = rows.add(this.prSectionRow(section)) };
		view = View(parent).layout_(VLayout(*rows).margins_(0).spacing_(6));
		classCtrl = SimpleController(cls)
			.put(\presets, { { this.refresh }.defer })
			.put(\clipboard, { { this.refresh }.defer });
		this.prWatch;
		this.refresh;
		^this
	}

	prWatch {
		ctrl = SimpleController(unit)
			.put(\levels, { { display !? (_.refresh); bars.refresh }.defer })
			.put(\enabled, { { this.refresh }.defer })
			.put(\settings, { { this.refresh }.defer })
			.put(\tidal, { { this.refresh }.defer })
			.put(\freed, { { this.prUnitGone }.defer });
		cls.paramNames.do { |key|
			ctrl.put(key, { { this.prRefreshOne(key); this.prRefreshPreset; display !? (_.refresh) }.defer });
		};
	}

	prUnitGone {
		ctrl !? (_.remove);
		ctrl = nil;
		unit = nil;
		bars.unit = nil;
		display !? { |d| d.unit = nil };
	}

	// the meters and the display run while the panel is on screen
	running_ { |bool|
		running = bool;
		unit !? { unit.meter = bool };
		display !? { |d| d.running = bool };
	}

	running { ^running }

	remove {
		ctrl !? (_.remove);
		ctrl = nil;
		classCtrl !? (_.remove);
		classCtrl = nil;
		display !? (_.remove);
		unit !? { unit.meter = false };
	}

	// ---------------------------------------------------------------- hints

	hints_ { |bar|
		hints = bar;
		if (bar.isNil) { ^this };
		bar.attach(onBox, "switch it on - off, it costs nothing, or the tail rings out and then nothing");
		bar.attach(tidalBox, "while this is on, a Tidal pattern sets the parameters shown in green by those names: s \"name\" # key value");
		bar.attach(presetMenu,
			"ready made settings, then any you have saved - reads (custom) once you move something");
		bar.attach(nameField, "a name to keep the current settings under");
		bar.attach(saveBtn, "keep these settings under that name - in this session and the next");
		bar.attach(delBtn, "drop the preset now selected, if it is one of yours");
		bar.attach(copyBtn, "put these settings aside - they can be pasted onto another of the same effect");
		bar.attach(pasteBtn, "take the copied settings onto this one - not whether it is on");
		bar.attach(bars.view, "input and output, with their peaks held - click to drop the marks");
		display !? { |d| bar.attach(d.view, { d.hint }) };
		cls.table.do { |e|
			widgets[e[\key]] !? { |w| bar.attach(w[\views], e[\hint]) };
		};
		^this
	}

	hints { ^hints }

	// ----------------------------------------------------------------- rows

	prTopRow {
		onBox = CheckBox().string_(cls.title ++ " on")
			.action_({ |v| unit !? (_.enabled_(v.value)) });
		// only when something plays it from Tidal (unit.tidal)
		tidalBox = CheckBox().string_("listen to tidal").visible_(false)
			.action_({ |v| unit !? { |u| u.tidal !? { |t| t.listening = v.value } } });
		presetMenu = PopUpMenu().fixedWidth_(120)
			.items_(cls.presetNames.collect(_.asString) ++ ["(custom)"])
			.action_({ |v|
				var names = cls.presetNames;
				if (v.value < names.size) { unit !? (_.preset_(names[v.value])) };
			});
		nameField = TextField().fixedWidth_(92).string_("");
		nameField.action = { this.prSavePreset };
		saveBtn = FxPalette.button("save", 42, { this.prSavePreset });
		delBtn = FxPalette.button("delete", 50, {
			var name = unit !? (_.presetName);
			name !? { cls.removePreset(name) };
		});
		copyBtn = FxPalette.button("copy", 44, { unit !? (_.copySettings) });
		pasteBtn = FxPalette.button("paste", 46, { unit !? (_.pasteSettings) });
		^HLayout(*[
			onBox, tidalBox,
			FxPalette.label("preset", 40), presetMenu,
			nameField, saveBtn, delBtn,
			copyBtn, pasteBtn,
			nil
		] ++ cls.panelExtras(this)).spacing_(6)
	}

	prSavePreset {
		var typed = nameField.string.stripWhiteSpace;
		var name = if (typed.isEmpty) { unit !? (_.presetName) } { typed };
		if (unit.isNil) { ^this };
		if (name.isNil) {
			("%: type a name to keep these settings under".format(cls.name)).warn;
			^this
		};
		if (unit.saveAsPreset(name).notNil) { nameField.string = "" };
	}

	// a row is its name, then a column per parameter in the table's order
	prSectionRow { |section|
		var items = [FxPalette.label(section.asString, 54, \left)];
		cls.table.do { |e|
			if (e[\section] == section) { items = items.add(this.prWidget(e)) };
		};
		^HLayout(*(items ++ [nil])).spacing_(6)
	}

	prWidget { |e|
		var key = e[\key], label = FxPalette.label(e[\label], nil, \center);
		^switch (e[\kind],
			\menu, {
				var menu = PopUpMenu().fixedWidth_(this.prMenuWidth(e))
					.items_(e[\items].collect(_.asString))
					.action_({ |v| unit !? (_.set(key, e[\items][v.value ? 0])) });
				widgets[key] = (views: [label, menu], menu: menu, label: label);
				VLayout(label, menu, nil).spacing_(1).margins_(0)
			},
			\toggle, {
				var box = CheckBox().string_(e[\label])
					.action_({ |v| unit !? (_.set(key, v.value)) });
				widgets[key] = (views: [box], check: box, checkLabel: e[\label]);
				VLayout(nil, box, nil).spacing_(1).margins_(0)
			},
			{
				var spec = e[\spec];
				var knob = Knob().fixedSize_(30@30).mode_(\vert)
					.action_({ |k| unit !? (_.set(key, spec.map(k.value))) });
				var box = NumberBox().fixedWidth_(48).align_(\center)
					.decimals_(this.prDecimals(e))
					.action_({ |b| unit !? (_.set(key, this.prFromBox(e, b.value))) });
				widgets[key] = (views: [label, knob, box], knob: knob, box: box, label: label);
				VLayout(label, [knob, align: \center], box).spacing_(1).margins_(0)
			}
		)
	}

	prMenuWidth { |e|
		^(e[\items].collect { |s| s.asString.size }.maxItem * 8 + 28).clip(56, 130)
	}

	prDecimals { |e|
		^e[\dec] ?? {
			switch (e[\box],
				\ms, { 1 }, \pct, { 0 },
				{
					var s = e[\spec];
					case
					{ s.step >= 1 } { 0 }
					{ (s.maxval - s.minval) > 100 } { 0 }
					{ (s.maxval - s.minval) > 5 } { 1 }
					{ 2 }
				}
			)
		}
	}

	prToBox { |e, v|
		^switch (e[\box], \ms, { v * 1000 }, \pct, { v * 100 }, { v })
	}

	prFromBox { |e, v|
		^switch (e[\box], \ms, { v / 1000 }, \pct, { v / 100 }, { v })
	}

	// -------------------------------------------------------------- refresh

	refresh {
		if (view.isNil or: { view.isClosed }) { ^this };
		this.prRefreshPreset;
		pasteBtn.enabled = cls.hasClipboard;
		if (unit.isNil) { onBox.value = false; tidalBox.visible = false; ^this };
		onBox.value = unit.enabled;
		this.prRefreshTidal;
		cls.paramNames.do { |key| this.prRefreshOne(key) };
		display !? (_.refresh);
		bars.refresh;
	}

	// the listen switch, and every parameter a pattern sets named by its Tidal
	// key, in green, while it listens - the key is what follows the effect's
	// name in # fx "growl" "drive1"; the others as the table labels them
	prRefreshTidal {
		var t = unit !? { unit.tryPerform(\tidal) }, on = false, keys = [];
		tidalBox.visible = t.notNil;
		if (t.notNil) {
			on = t.listening == true;
			keys = if (on) { t.tidalKeys ? [] } { [] };
			tidalBox.value = on;
			tidalBox.string = "listen to tidal as " ++ t.tidalName;
		};
		if (tidalShown == [t !? { t.tidalName }, keys]) { ^this };
		tidalShown = [t !? { t.tidalName }, keys];
		cls.table.do { |e|
			var w = widgets[e[\key]], tidal = keys.includes(e[\key]);
			w !? {
				w[\label] !? { |l|
					l.string = if (tidal) { e[\key].asString } { e[\label] };
					l.stringColor = if (tidal) { this.class.tidalColour } { FxPalette.dim };
				};
				w[\check] !? { |c| c.string = if (tidal) { e[\key].asString } { w[\checkLabel] } };
			}
		}
	}

	// what a parameter's name is drawn in while Tidal plays it
	*tidalColour { ^Color(0.55, 0.85, 0.55) }

	prRefreshOne { |key|
		var e = cls.param(key), w = widgets[key], v;
		if (w.isNil or: { unit.isNil } or: { view.isClosed }) { ^this };
		v = unit.get(key);
		w[\menu] !? { |m| m.value = e[\items].indexOf(v) ? 0 };
		w[\check] !? { |c| c.value = v };
		w[\knob] !? { |k|
			k.value = e[\spec].unmap(v);
			w[\box].value = this.prToBox(e, v);
		};
	}

	prRefreshPreset {
		var names = cls.presetNames, items, pre;
		if (view.isNil or: { view.isClosed }) { ^this };
		items = names.collect(_.asString) ++ ["(custom)"];
		if (presetMenu.items != items) { presetMenu.items = items };
		pre = unit !? (_.presetName);
		presetMenu.value = (pre !? { names.indexOf(pre) }) ? names.size;
		delBtn.enabled = pre.notNil and: { cls.isUserPreset(pre) };
	}
}


// =====================================================================
// FxParamGUI - one effect in a window of its own: what `unit.gui` opens
// =====================================================================

FxParamGUI {
	var <unit, <window, <panel, <hints;
	var statusText, ctrl;

	*new { |unit, x, y| ^super.new.init(unit, x, y) }

	init { |argUnit, x, y|
		var size = argUnit.class.windowSize;
		unit = argUnit;
		window = Window(this.prTitle, Rect(x ? 100, y ? 100, size.x, size.y));
		FxPalette.darken(window);
		panel = FxParamPanel(unit);
		hints = FxHintBar();
		statusText = StaticText().font_(FxPalette.smallFont).stringColor_(FxPalette.dim);
		window.layout = VLayout(
			[panel.view, stretch: 1],
			HLayout(statusText, nil, hints.toggle).spacing_(4),
			hints.view
		).margins_(8).spacing_(6);
		hints.watch(window);
		panel.hints = hints;
		hints.attach(statusText, "where this effect is running");
		ctrl = SimpleController(unit)
			.put(\enabled, { { this.prRefreshStatus }.defer })
			.put(\node, { { this.prRefreshStatus }.defer })
			.put(\label, { { this.prRefreshStatus }.defer })
			.put(\freed, { { this.close }.defer });
		window.onClose = {
			ctrl.remove;
			panel.remove;
			unit.prGuiClosed;
		};
		panel.running = true;
		this.prRefreshStatus;
		window.front;
		hints.toggle.value = true;		// it read `visible` before there was a window, which answers false
		^this
	}

	prTitle { ^unit.class.title ++ " · " ++ unit.label }

	front { window !? { if (window.isClosed.not) { window.front } } }

	close { window !? { if (window.isClosed.not) { window.close } } }

	prRefreshStatus {
		var parts = [];
		if (window.isClosed) { ^this };
		window.name = this.prTitle;
		case
		{ unit.chain.notNil } { parts = parts.add("in " ++ unit.chain.name) }
		{ unit.isDriving } { parts = parts.add("driving " ++ unit.target.asString) }
		{ unit.isOnBus } { parts = parts.add("bus " ++ unit.bus) }
		{ true } { parts = parts.add("not running anywhere yet") };
		unit.numChannels !? { |n| parts = parts.add(n.asString ++ " ch") };
		parts = parts.add(if (unit.enabled) { "on" } { "off" });
		if (unit.isOnBus and: { unit.enabled } and: { unit.synth.isNil }) {
			parts = parts.add("waiting for the server");
		};
		statusText.string = parts.join(" · ");
	}
}
