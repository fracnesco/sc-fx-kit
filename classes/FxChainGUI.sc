// =====================================================================
// FxChainPanel - a chain's effects, one at a time; FxChainGUI - in a window
//
//   (o) master_localhost  bus 0 · 2 ch   [presets v] save load      + eq  + compressor  [+ effect v]
//   [ 1 eq | 2 compressor ]                                            earlier  later  remove
//   +---------------------------------------------------------------------------------------+
//   |  the effect shown: its own panel, header, display, cards - as in its own window        |
//   +---------------------------------------------------------------------------------------+
//
// The chain's row - on/off, its name and bus, the chain presets, a button
// per effect class that is installed - then a tab per effect, in the order
// the sound goes through them, left to right, and under the tabs the panel
// of the effect chosen: the same panel its own window shows. One at a time,
// because an effect's panel is a device now (sc-fx-ui) - a display and its
// cards - and two of them no longer stack in one window. earlier and later
// move the effect shown one place along the chain, remove takes it out.
// The panel follows the chain: an effect added from code turns up (and is
// shown), one removed goes away. ServerPanel opens it from its fx button;
// `gui` opens it in a window with a hint bar.
//
//   panel = FxChainPanel(chain);
//   window.layout = VLayout(panel.view, hints.view);
//   panel.hints = hints;
//   panel.running = true;          // the analysers of the effect shown
//   panel.shown = 1;               // the second effect
//   panel.remove;                  // in window.onClose
// =====================================================================

FxChainPanel {
	var <chain, <view, <panels, <shown = 0;
	var power, nameText, infoText, presetChoice, saveBtn, loadBtn, addBtns, effectChoice, effectClasses;
	var tabs, tabsHolder, earlierBtn, laterBtn, dropBtn, body, stackView, stack;
	var ctrl, unitCtrls, hints, running = false, presetPaths, shownUnit, lastCount = 0;

	*new { |chain, parent| ^super.new.init(chain, parent) }

	init { |argChain, parent|
		chain = argChain;
		panels = [];
		unitCtrls = [];
		tabsHolder = View().layout_(HLayout().margins_(0).spacing_(0));
		// words, not < >: the effect's own header steps its presets with those
		earlierBtn = FxButton("earlier", { this.prMove(-1) });
		laterBtn = FxButton("later", { this.prMove(1) });
		dropBtn = FxButton("remove", { this.prDrop });
		body = View().layout_(VLayout().margins_(0).spacing_(0));
		view = View(parent).layout_(VLayout(
			this.prHeaderRow,
			HLayout(tabsHolder, nil, earlierBtn.view, laterBtn.view, dropBtn.view).margins_(0).spacing_(6),
			[body, stretch: 1]
		).margins_(0).spacing_(8));
		ctrl = SimpleController(chain)
			.put(\units, { { this.prRebuild }.defer })
			.put(\enabled, { { this.refresh }.defer })
			.put(\presets, { { this.refresh }.defer })
			.put(\freed, { { this.prRebuild }.defer });
		this.prRebuild;
		^this
	}

	remove {
		ctrl !? (_.remove);
		ctrl = nil;
		this.prForgetUnits;
		panels.do(_.remove);
		panels = [];
	}

	// The analyser of the effect shown, while the panel is showing: the
	// others are out of sight, so they cost nothing.
	running_ { |bool|
		running = bool == true;
		this.prRun;
	}

	running { ^running }

	// which effect the panel shows, by its place in the chain
	shown_ { |i|
		if (panels.isEmpty) { shown = 0; ^this };
		shown = (i ? 0).asInteger.clip(0, panels.size - 1);
		shownUnit = chain.units[shown];
		stack !? { stack.index = shown };
		tabs !? { tabs.value = shown };
		this.prRun;
		this.refresh;
	}

	// the panel of the effect shown, or nil
	shownPanel { ^panels[shown] }

	hints_ { |bar|
		hints = bar;
		if (bar.isNil) { ^this };
		bar.attach(power.view,
			"the whole chain in or out - out, every effect's node is faded away and the bus is left alone");
		bar.attach([nameText, infoText], "this chain: its name, its bus, its channels");
		bar.attach(presetChoice.view, "the chain presets saved so far, newest first - pick one to load it");
		bar.attach(saveBtn.view, "write every effect's settings to one preset file");
		bar.attach(loadBtn.view, "read a chain preset back in, effect by effect");
		addBtns.do { |b| bar.attach(b.view, "another effect at the end of the chain") };
		effectChoice !? { |c| bar.attach(c.view, "another effect at the end of the chain - any of sc fx kit's") };
		bar.attach(earlierBtn.view, "the effect shown one place earlier in the chain");
		bar.attach(laterBtn.view, "the effect shown one place later in the chain");
		bar.attach(dropBtn.view, "take the effect shown out of the chain");
		tabs !? { |t|
			bar.attach(t.view, "the chain's effects, in the order the sound meets them - click one for its"
				" controls; dim is off");
		};
		panels.do { |p| p.hints = bar };
		^this
	}

	hints { ^hints }

	// --------------------------------------------------------------- rows

	prHeaderRow {
		var items;
		power = FxPower().action_({ |p| chain.enabled_(p.value) });
		nameText = StaticText().font_(FxPalette.boldFont).stringColor_(FxPalette.text);
		infoText = StaticText().font_(FxPalette.smallFont).stringColor_(FxPalette.dim);
		presetChoice = FxChoice(nil, [], 140).action_({ |c|
			var path = presetPaths !? { presetPaths[c.value] };
			path !? { chain.loadPreset(path) };
			{ this.refresh }.defer;
		});
		saveBtn = FxButton("save", {
			Dialog.savePanel({ |p| chain.savePreset(p) }, path: FxChain.ensurePresetDir);
		});
		loadBtn = FxButton("load", {
			Dialog.openPanel({ |p| chain.loadPreset(p) }, path: FxChain.ensurePresetDir);
		});
		// one button per effect class that is installed - the compressor
		// is looked up by name, so this compiles without it
		addBtns = [[\ParaEQ, "+ eq"], [\Compressor, "+ compressor"]].collect { |pair|
			pair[0].asClass !? { |cls| FxButton(pair[1], { chain.add(cls.new) }) }
		}.reject(_.isNil);
		// the effects of sc fx kit, each its own extension: a menu, since there can be many
		\FxParamUnit.asClass !? { |base|
			effectClasses = base.allSubclasses.reject { |c| c.name.asString.beginsWith("Meta_") }
				.sort { |a, b| a.name < b.name };
			if (effectClasses.notEmpty) {
				effectChoice = FxChoice(nil, effectClasses.collect { |c| c.title }, 110).action_({ |c|
					effectClasses[c.value] !? { |cls| chain.add(cls.new) };
				});
				effectChoice.override = "+ effect";
			};
		};
		items = [power.view, nameText, infoText, View().fixedWidth_(8), presetChoice.view,
			saveBtn.view, loadBtn.view, nil] ++ addBtns.collect(_.view) ++ (effectChoice !? { [effectChoice.view] } ? []);
		^HLayout(*items).margins_(0).spacing_(6)
	}

	// One tab per effect and one panel each, in a stack that shows one. The
	// effect shown stays shown when the chain moves it, and one just added
	// is shown.
	prRebuild {
		var units, labels, colours;
		if (view.isNil or: { view.isClosed }) { ^this };
		this.prForgetUnits;
		panels.do(_.remove);
		tabs !? { tabs.view.remove };
		stackView !? { stackView.remove };
		tabs = nil;
		stack = nil;
		stackView = nil;
		units = chain.units;
		panels = units.collect { |unit| unit.panel };
		shown = case
		{ units.size > lastCount and: { lastCount > 0 } } { units.size - 1 }
		{ shownUnit.notNil and: { units.includes(shownUnit) } } { units.indexOf(shownUnit) }
		{ true } { shown.clip(0, (units.size - 1).max(0)) };
		lastCount = units.size;
		shownUnit = units[shown];
		if (units.notEmpty) {
			labels = units.collect { |u, i| "%  %".format(i + 1, this.prUnitName(u)) };
			colours = units.collect { |u| u.class.tryPerform(\identityColour) };
			tabs = FxSwitch(labels).itemColours_(colours).value_(shown)
				.action_({ |s| this.shown = s.value });
			tabsHolder.layout.add(tabs.view);
			stack = StackLayout(*panels.collect(_.view));
			stackView = View().layout_(stack);
			body.layout.add(stackView);
			stack.index = shown;
		};
		// a unit switched on or off from its own panel dims or lights its tab
		unitCtrls = units.collect { |u|
			SimpleController(u).put(\enabled, { { this.refresh }.defer })
		};
		this.prRun;
		hints !? { this.hints_(hints) };
		this.refresh;
	}

	prForgetUnits {
		unitCtrls.do(_.remove);
		unitCtrls = [];
	}

	prRun {
		panels.do { |p, i| p.tryPerform(\running_, running and: { i == shown }) };
	}

	// "eq", "compressor", "growl": what the effect calls itself
	prUnitName { |u|
		^(u.class.tryPerform(\title) ?? { u.label }).asString.toLower
	}

	prMove { |delta|
		var units = chain.units, to = shown + delta;
		if (units.isEmpty or: { to < 0 } or: { to >= units.size }) { ^this };
		shownUnit = units[shown];
		chain.moveUnit(shown, to);
	}

	prDrop {
		var unit = chain.units[shown];
		unit !? { chain.remove(unit) };
	}

	refresh {
		var units;
		if (view.isNil or: { view.isClosed }) { ^this };
		units = chain.units;
		power.value = chain.enabled;
		nameText.string = chain.name.asString;
		infoText.string = "bus % · % ch".format(chain.bus, chain.numChannels);
		presetPaths = FxChain.presets.keep(12);
		presetChoice.items = presetPaths.collect { |p| PathName(p).fileNameWithoutExtension };
		presetChoice.override = if (presetPaths.isEmpty) { "no presets yet" } { "presets" };
		presetChoice.inactive = presetPaths.isEmpty;
		tabs !? { |t|
			t.value = shown;
			t.inactiveItems = units.collect { |u, i| if (u.enabled.not) { i } }.reject(_.isNil);
		};
		earlierBtn.enabled = shown > 0;
		laterBtn.enabled = shown < (units.size - 1);
		dropBtn.enabled = units.notEmpty;
	}
}


// =====================================================================
// FxChainGUI - a chain in a window of its own: what `chain.gui` opens
//
// The panel above, and the one-line footer every effect window has: the
// hint at the left, the chain at the right. As tall as the tallest of its
// effects' own windows, and the chain's two rows on top.
// =====================================================================

FxChainGUI {
	var <chain, <window, <panel, <hints;
	var ctrl, statusText;

	*new { |chain, x, y| ^super.new.init(chain, x, y) }

	init { |argChain, x, y|
		var h;
		chain = argChain;
		h = chain.units.collect { |u| u.class.tryPerform(\windowSize) !? (_.y) ? 560 }.maxItem ? 560;
		window = Window("fx · " ++ chain.name, Rect(x ? 100, y ? 100, 1000, (h + 56).clip(600, 720)));
		FxPalette.darken(window);

		panel = FxChainPanel(chain);
		hints = FxHintBar();
		statusText = StaticText().font_(FxPalette.smallFont).stringColor_(FxPalette.dim).align_(\right);
		hints.view.layout.add(statusText);

		window.layout = VLayout([panel.view, stretch: 1], hints.view).margins_(8).spacing_(8);

		hints.watch(window);
		panel.hints = hints;
		hints.attach(statusText, "where this chain is running");

		ctrl = SimpleController(chain)
			.put(\freed, { { this.close }.defer })
			.put(\enabled, { { this.prRefreshStatus }.defer })
			.put(\units, { { this.prRefreshStatus }.defer });

		window.onClose = {
			ctrl.remove;
			panel.remove;
			chain.prGuiClosed;
		};
		panel.running = true;
		this.prRefreshStatus;
		window.front;
		^this
	}

	prRefreshStatus {
		if (window.isClosed) { ^this };
		statusText.string = [
			"bus " ++ chain.bus, chain.numChannels.asString ++ " ch",
			chain.units.size.asString ++ if (chain.units.size == 1) { " effect" } { " effects" },
			if (chain.enabled) { "on" } { "off" }
		].join(" · ");
	}

	front { window !? { if (window.isClosed.not) { window.front } } }

	close { window !? { if (window.isClosed.not) { window.close } } }
}
