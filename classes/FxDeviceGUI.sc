// =====================================================================
// FxDeviceGUI - one effect in a window of its own (sc-fx-ui): the unit's
// panel, then a one-line footer - the hint at the left, where it runs at the
// right. Any FxUnit whose *panelClass builds a device panel (FxDevicePanel,
// ParaEQPanel, CompressorPanel) opens in it:
//
//   *guiClass { ^FxDeviceGUI }    *windowSize { ^Point(960, 560) }
//
// The panel is asked for hints_, running_ and remove, as every panel is.
// (Moved here from sc fx kit on 2026-11-09, so hand-built panels share it.)
// =====================================================================

FxDeviceGUI {
	var <unit, <window, <panel, <hints;
	var statusText, ctrl;

	*new { |unit, x, y| ^super.new.init(unit, x, y) }

	init { |argUnit, x, y|
		var size = argUnit.class.tryPerform(\windowSize) ?? { Point(960, 560) };
		unit = argUnit;
		window = Window(this.prTitle, Rect(x ? 100, y ? 100, size.x, size.y));
		FxPalette.darken(window);
		panel = unit.class.panelClass.new(unit);
		hints = FxHintBar();
		statusText = StaticText().font_(FxPalette.smallFont).stringColor_(FxPalette.dim).align_(\right);
		hints.view.layout.add(statusText);
		window.layout = VLayout([panel.view, stretch: 1], hints.view).margins_(10).spacing_(8);
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
		panel.tryPerform(\running_, true);
		this.prRefreshStatus;
		window.front;
		^this
	}

	prTitle {
		var t = unit.class.tryPerform(\title) ?? { unit.class.name.asString.toLower };
		^t ++ " · " ++ (unit.tryPerform(\label) ? "")
	}

	front { window !? { if (window.isClosed.not) { window.front } } }

	close { window !? { if (window.isClosed.not) { window.close } } }

	prRefreshStatus {
		var parts = [];
		if (window.isClosed) { ^this };
		window.name = this.prTitle;
		case
		{ unit.tryPerform(\chain).notNil } { parts = parts.add("in " ++ unit.chain.name) }
		{ unit.tryPerform(\isDriving) == true } { parts = parts.add("driving " ++ unit.target.asString) }
		{ unit.tryPerform(\isOnBus) == true } { parts = parts.add("bus " ++ unit.bus) }
		{ true } { parts = parts.add("not running") };
		unit.tryPerform(\numChannels) !? { |n| parts = parts.add(n.asString ++ " ch") };
		parts = parts.add(if (unit.tryPerform(\enabled) == true) { "on" } { "off" });
		if ((unit.tryPerform(\isOnBus) == true) and: { unit.enabled } and: { unit.tryPerform(\synth).isNil }) {
			parts = parts.add("waiting for the server");
		};
		statusText.string = parts.join(" · ");
	}
}
