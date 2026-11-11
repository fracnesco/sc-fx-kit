// =====================================================================
// FxDevicePanel - an FxParamUnit as a device (the sc-fx-ui rules); its
// window is FxDeviceGUI (sc EQ)
//
//   (o) GROWL   < warm tape *     v >  save  ...                  tidal  extras
//   +-----------------------------------------------------------+ +------+
//   |  the effect's display                                      | |in out|
//   +-----------------------------------------------------------+ +------+
//   [ drive ][ feedback ][ modulation ]          <- pages, if more than one
//   | input .... | | stage   1 2 3 ........ | | post ... |   | output .... |
//   |  ( )  ( )  | |  (( ))  ( )  ( )  ( )  | |  ( ) ( ) |   |  ( )   ( )  |
//   |  0.0 dB    | |  12.0 dB ...           | |          |   |  35 %  0 dB |
//
// The frame is shared, the face is the effect's family (the skill's "Family
// faces"): the pages and cards come from the class (*devicePages), what is
// dimmed from *isActive, the knobs' size and arcs from the table (size:,
// bipolar:), the hero knobs and the name wear *identityColour. What the
// table says becomes:
//
//   knob                      FxKnob (size \big / \medium / \small)
//   menu, 2-4 items           FxSwitch, segmented, captioned (style: \menu for a menu)
//   menu, more items          FxChoice
//   menu, style: \knob        FxKnob stepping through the items, a tick each (divisions)
//   toggle                    FxSwitch, one lit segment; size: \big makes it an FxPad
//                             (a latch: click, or hold for momentary - freeze, hold)
//
// and a card's keys may also hold Events - things that are not parameters:
//
//   (button: "go", action: { |unit, panel| ... }, size: \big, hint: "...")
//                             a trigger pad (big) or a button
//   (choice: "chord", items: [...], action: { |unit, item, index, panel| }, hint: "...")
//                             a menu that does something, showing its label
//   (swap: [\leftDiv, \left], when: { |unit| unit.sync })
//                             one slot, the first key while `when` is true
//
// The same messages as FxParamPanel, so a chain shows it too:
//
//   panel = FxDevicePanel(unit);
//   panel.hints = hints; panel.running = true; ... panel.remove;
//   panel.page = 1;  panel.focus = 2;
//
// An effect switches to it with
//   *guiClass { ^FxDeviceGUI }   *panelClass { ^FxDevicePanel }
// =====================================================================

FxDevicePanel {
	var <unit, <cls, <view, <display, <meter, <header;
	var tidalSwitch, <tabs, stack, <focus = 0;
	var <widgets, entries, groups, cards, swaps, actions;
	var ctrl, classCtrl, hints, running = false, tidalShown;

	*new { |unit, parent| ^super.new.init(unit, parent) }

	init { |argUnit, parent|
		var displayRow;
		unit = argUnit;
		cls = unit.class;
		widgets = IdentityDictionary.new;
		entries = IdentityDictionary.new;
		groups = [];
		cards = [];
		swaps = [];
		actions = [];
		meter = FxMeter(unit);
		display = cls.displayClass !? { |d| d.new(unit) };
		display !? { |d| if (d.respondsTo(\focusAction_)) { d.focusAction = { |i| this.focus_(i) } } };
		displayRow = HLayout(*((display !? { [[display.view, stretch: 1]] } ? [nil]) ++ [meter.view])).spacing_(8);
		view = View(parent).layout_(VLayout(
			this.prHeader,
			[displayRow, stretch: 1],
			this.prControls
		).margins_(0).spacing_(8));
		classCtrl = SimpleController(cls)
			.put(\presets, { { this.refresh }.defer })
			.put(\clipboard, { { this.refresh }.defer });
		this.prWatch;
		this.refresh;
		^this
	}

	identity { ^cls.tryPerform(\identityColour) }

	prWatch {
		ctrl = SimpleController(unit)
			.put(\levels, { {
				display !? { |d| d.tryPerform(\levelsArrived); d.refresh };
				meter.refresh;
			}.defer })
			.put(\enabled, { { this.refresh }.defer })
			.put(\settings, { { this.refresh }.defer })
			.put(\tidal, { { this.refresh }.defer })
			.put(\freed, { { this.prUnitGone }.defer });
		cls.paramNames.do { |key|
			ctrl.put(key, { {
				this.prRefreshOne(key);
				header.refresh;
				this.prRefreshActive;
				display !? (_.refresh);
			}.defer });
		};
	}

	prUnitGone {
		ctrl !? (_.remove);
		ctrl = nil;
		unit = nil;
		meter.unit = nil;
		header.unit = nil;
		display !? { |d| d.unit = nil };
	}

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

	isClosed { ^view.isNil or: { view.isClosed } }

	// ---------------------------------------------------------------- header

	prHeader {
		tidalSwitch = FxSwitch(["tidal"], true).action_({ |s|
			unit !? { |u| u.tidal !? { |t| t.listening = s.value } }
		});
		tidalSwitch.view.visible = false;
		header = FxHeader(unit, cls.title, this.identity, [tidalSwitch.view] ++ cls.panelExtras(this));
		^header.view
	}

	// -------------------------------------------------------------- controls

	prControls {
		var pages = cls.devicePages, pageViews, outCard, row;
		pageViews = pages.collect { |assoc|
			var cs = assoc.value.collect { |spec| this.prCard(spec) };
			View().layout_(HLayout(*(cs.collect(_.view) ++ [nil])).margins_(0).spacing_(10))
		};
		// every page as tall as the tallest, so the display keeps its size from page to page
		pageViews.collect { |v| v.sizeHint.height }.maxItem !? { |h| pageViews.do { |v| v.minHeight = h } };
		stack = StackLayout(*pageViews);
		tabs = FxSwitch(pages.collect { |a| a.key.asString }).action_({ |t| stack.index = t.value });
		tabs.view.visible = pages.size > 1;
		outCard = if (cls.sections.includes(\output)) { this.prCard(\output) };
		row = HLayout(*([[View().layout_(stack), stretch: 1]] ++ (outCard !? { [outCard.view] } ? []))).spacing_(10);
		^VLayout(HLayout(tabs.view, nil).margins_(0), row).margins_(0).spacing_(8)
	}

	page_ { |i| tabs !? { tabs.valueAction = i.clip(0, tabs.items.size - 1) } }
	page { ^tabs !? (_.value) ? 0 }

	prColour { |c, i| ^case { c == \identity } { this.identity } { c.isKindOf(Function) } { c.value(i) } { true } { c } }

	// a card from a section's name or an Event (see FxParamUnit:*devicePages)
	prCard { |spec|
		var title, keyLists, colours, count, contents, card, selector, cstack;
		if (spec.isKindOf(Symbol)) {
			title = spec.asString;
			keyLists = [cls.table.select { |e| e[\section] == spec }.collect { |e| e[\key] }];
			colours = [nil];
			count = 1;
		} {
			title = spec[\title] ? "";
			count = spec[\count];
			if (count.notNil) {
				keyLists = count.collect { |i| spec[\keys].value(i) };
				colours = count.collect { |i| this.prColour(spec[\colour], i) };
			} {
				count = 1;
				keyLists = [spec[\keys]];
				colours = [this.prColour(spec[\colour])];
			};
		};
		contents = keyLists.collect { |keys, i| this.prContent(keys, colours[i]) };
		if (count > 1) {
			cstack = StackLayout(*contents.collect { |l| View().layout_(l) });
			selector = FxSwitch(count.collect { |i| (i + 1).asString }).action_({ |s| this.focus_(s.value) });
			card = FxCard(title, View().layout_(cstack), colours[0], [selector.view]);
			groups = groups.add((card: card, stack: cstack, selector: selector, keys: keyLists, colours: colours));
		} {
			card = FxCard(title, contents[0], colours[0]);
		};
		// a card grows to share the width, but not so far that its controls drift apart
		card.view.maxWidth = (card.view.sizeHint.width * 1.6).ceil;
		cards = cards.add(card);
		^card
	}

	// one card's cells, left to right, spread when the card is given room
	prContent { |keys, colour|
		var cells = keys.collect { |k| this.prCell(k, colour) }, items = [];
		cells.do { |c, i| items = items.add([c, align: \top]); if (i < (cells.size - 1)) { items = items.add(nil) } };
		^HLayout(*items).margins_(0).spacing_(6)
	}

	prCell { |key, colour|
		var e, w;
		case
		{ key.isKindOf(Event) } { ^this.prActionCell(key, colour) }
		{ key.isArray and: { key.isString.not } } {
			^VLayout(*(key.collect { |k| this.prCell(k, colour) } ++ [nil])).margins_(0).spacing_(8)
		};
		e = cls.param(key);
		if (e.isNil) { ("%: no parameter %".format(cls.name, key)).warn; ^View() };
		entries[key] = e;
		w = switch (e[\kind],
			\menu, {
				case
				{ e[\style] == \knob } {
					// a knob over the items: steps with ticks (note values, divisions)
					FxKnob(e[\label], ControlSpec(0, 1), e[\size] ? \medium, false,
						colour ?? { if (e[\size] == \big) { this.identity } })
						.stepNames_(e[\items])
						.default_(e[\items].indexOf(e[\default]) ? 0)
						.hint_(e[\hint] ? "")
						.action_({ |k| unit !? (_.set(key, e[\items][k.mapped.round.asInteger])) })
				}
				{ (e[\style] != \menu) and: { e[\items].size <= 4 } } {
					FxSwitch(e[\items], false, nil, e[\label])
						.action_({ |s| unit !? (_.set(key, e[\items][s.value])) })
				}
				{ true } {
					FxChoice(e[\label], e[\items], FxChoice.widthFor(e[\items]))
						.action_({ |c| unit !? (_.set(key, e[\items][c.value])) })
				}
			},
			\toggle, {
				if (e[\size] == \big) {
					FxPad(e[\label], \latch).action_({ |p| unit !? (_.set(key, p.value)) })
						.outline_(colour ?? { this.identity })
				} {
					FxSwitch([e[\label]], true).action_({ |s| unit !? (_.set(key, s.value)) })
				}
			},
			{
				var size = e[\size] ? \medium;
				var k = FxKnob(e[\label], e[\spec], size, e[\bipolar] ? false,
					cls.tryPerform(\keyColour, key) ?? colour ?? { if (size == \big) { this.identity } });
				k.default = e[\default] ? e[\spec].default;
				k.box = e[\box];
				k.dec = e[\dec];
				k.floorText = e[\floorText];
				k.hint = e[\hint] ? "";
				k.menuItems = e[\menuItems];
				k.action = { |kn| unit !? (_.set(key, kn.mapped)) };
				k
			}
		);
		widgets[key] = w;
		^if ((e[\kind] == \toggle) and: { e[\size] != \big }) {
			VLayout(View().fixedHeight_(16), w.view, nil).margins_(0).spacing_(0)
		} { w.view }
	}

	// a cell for what is not a parameter: a pad or button, a menu that acts,
	// or a slot that shows one of two parameters
	prActionCell { |e, colour|
		var w, a, b, sl, asView;
		case
		{ e[\button].notNil } {
			w = if (e[\size] == \big) {
				FxPad(e[\button], \trigger, caption: e[\caption]).outline_(colour ?? { this.identity })
					.action_({ unit !? { |u| e[\action].value(u, this) } })
			} {
				FxButton(e[\button], { unit !? { |u| e[\action].value(u, this) } })
			};
			actions = actions.add((widget: w, hint: e[\hint] ? ""));
			^if (e[\size] == \big) { w.view } { VLayout(View().fixedHeight_(16), w.view, nil).margins_(0).spacing_(0) }
		}
		{ e[\choice].notNil } {
			w = FxChoice(e[\choice], e[\items], e[\width] ?? { FxChoice.widthFor(e[\items]) })
				.action_({ |c| unit !? { |u| e[\action].value(u, e[\items][c.value], c.value, this) } });
			e[\prompt] !? { |p| w.override = p };
			actions = actions.add((widget: w, hint: e[\hint] ? ""));
			^w.view
		}
		{ e[\swap].notNil } {
			a = this.prCell(e[\swap][0], colour);
			b = this.prCell(e[\swap][1], colour);
			asView = { |x| if (x.isKindOf(View)) { x } { View().layout_(x) } };
			sl = StackLayout(asView.(a), asView.(b));
			swaps = swaps.add((stack: sl, when: e[\when]));
			^View().layout_(sl)
		}
		{ true } { ("%: a card item with nothing to do: %".format(cls.name, e)).warn; ^View() }
	}

	// which instance of a repeated card shows (Growl's stage 1, 2, 3)
	focus_ { |i|
		focus = i;
		groups.do { |g|
			var n = i.clip(0, g[\keys].size - 1);
			g[\stack].index = n;
			g[\selector].value = n;
			g[\card].colour = g[\colours][n];
		};
		display.tryPerform(\focus_, i);
	}

	// ----------------------------------------------------------------- hints

	hints_ { |bar|
		hints = bar;
		if (bar.isNil) { ^this };
		header.hints = bar;
		bar.attach(tidalSwitch.view, "a Tidal pattern sets the parameters whose names turn green while this is lit");
		bar.attach(tabs.view, "the pages of controls - the display and the output stay");
		bar.attach(meter.view, "in and out, with the peaks held - click to drop the marks and the clip lights");
		display !? { |d| bar.attach(d.view, { |v, x, y| d.hint(x, y) }) };
		groups.do { |g| bar.attach(g[\selector].view, "which one the card shows - or click it in the display") };
		actions.do { |a| bar.attach(a[\widget].view, a[\hint]) };
		widgets.keysValuesDo { |key, w|
			var e = entries[key];
			if (w.isKindOf(FxKnob)) {
				w.hints = bar;
				bar.attach(w.view, { "%  %   -   %".format(w.label, w.text, e[\hint]) });
			} {
				bar.attach(w.view, e[\hint]);
			};
		};
		^this
	}

	hints { ^hints }

	// --------------------------------------------------------------- refresh

	refresh {
		if (this.isClosed) { ^this };
		header.refresh;
		if (unit.isNil) { ^this };
		widgets.do { |w| w.tryPerform(\muted_, unit.enabled.not) };
		actions.do { |a| a[\widget].tryPerform(\muted_, unit.enabled.not) };
		this.prRefreshTidal;
		widgets.keysDo { |key| this.prRefreshOne(key) };
		this.prRefreshActive;
		display !? (_.refresh);
		meter.refresh;
	}

	prRefreshOne { |key|
		var e = entries[key], w = widgets[key], v;
		if (w.isNil or: { unit.isNil } or: { this.isClosed }) { ^this };
		v = unit.get(key);
		case
		{ w.isKindOf(FxKnob) } { w.mapped = if (w.stepNames.notNil) { e[\items].indexOf(v) ? 0 } { v } }
		{ w.isKindOf(FxChoice) } { w.value = e[\items].indexOf(v) ? 0 }
		{ w.isKindOf(FxPad) } { w.value = v == true }
		{ w.isKindOf(FxSwitch) } { w.value = if (w.toggle) { v == true } { e[\items].indexOf(v) ? 0 } };
	}

	prRefreshActive {
		if (unit.isNil or: { this.isClosed }) { ^this };
		widgets.keysValuesDo { |key, w| w.tryPerform(\inactive_, cls.isActive(key, unit).not) };
		groups.do { |g|
			g[\selector].inactiveItems = g[\keys].size.collect { |i| i }
				.select { |i| cls.isActive(g[\keys][i].flat.reject { |k| k.isKindOf(Event) }.first, unit).not };
		};
		swaps.do { |s|
			var i = if (s[\when].value(unit) == true) { 0 } { 1 };
			if (s[\stack].index != i) { s[\stack].index = i };
		};
	}

	prRefreshTidal {
		var t = unit !? { unit.tryPerform(\tidal) }, on = false, keys = [];
		tidalSwitch.view.visible = t.notNil;
		if (t.notNil) {
			on = t.listening == true;
			keys = if (on) { t.tidalKeys ? [] } { [] };
			tidalSwitch.value = on;
		};
		if (tidalShown == [t !? { t.tidalName }, keys]) { ^this };
		tidalShown = [t !? { t.tidalName }, keys];
		widgets.keysValuesDo { |key, w|
			var tidal = keys.includes(key), e = entries[key];
			w.tryPerform(\tidal_, tidal);
			if (w.isKindOf(FxPad)) {
				w.caption = if (tidal) { key.asString } { nil };
			} {
				if (w.respondsTo(\label_)) { w.label = if (tidal) { key.asString } { e[\label] } };
			};
		};
	}
}
