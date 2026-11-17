// =====================================================================
// FxHintBar - a strip that says what the mouse is over
//
// The same idea as ModuleHintBar in the module family and InsightHintBar
// in Insight, written here so this repository keeps depending on nothing:
// a line along the bottom of a window that describes whatever control
// the pointer is on, so a window full of small boxes can be read without
// a manual.
//
//   hints = FxHintBar();
//   hints.watch(window);                    // once, after the window exists
//   window.layout = VLayout(..., hints.view);
//
//   hints.attach(box, "what this box does");
//   hints.attach([label, slider, box], "the three parts of one control");
//   hints.attach(readout, { "now at " ++ thing.value });   // worked out on hover
//
// `attach` answers what it was given, so a control can be wrapped where it
// is built. A hint is a String, or a Function for one that depends on the
// moment. Moving onto nothing in particular puts the bar back to its
// default, and `toggle` is a checkbox that folds it away.
// =====================================================================

FxHintBar {
	classvar <>defaultHeight = 22;

	var <view, <text, <>defaultString;
	var toggleBox, shown = true;

	*new { |defaultString, height| ^super.new.init(defaultString, height) }

	init { |str, height|
		defaultString = str ?? {
			"hover over a control and this bar says what it does"
		};
		text = StaticText().font_(FxPalette.smallFont).stringColor_(FxPalette.dim)
			.string_(defaultString);
		view = View().background_(FxPalette.waveBg)
			.fixedHeight_(height ? defaultHeight);
		view.layout = HLayout(text).margins_([8, 1, 8, 1]);
		^this
	}

	// A child's mouse-over only arrives if the window is willing to hear
	// about it. Moving onto the window itself - the gaps between controls -
	// puts the bar back to its default, so a stale hint does not sit there
	// describing something the pointer left a while ago.
	watch { |window|
		var v = window.tryPerform(\view) ? window;
		v !? {
			v.acceptsMouseOver = true;
			v.mouseOverAction = { this.clear };
		};
		^this
	}

	// `hint` is a String, or a Function answering one - given the view and the
	// pointer's x and y in it, so a display can say what is under the pointer
	// ({ |view, x, y| ... }; a function that takes nothing ignores them).
	// Answers `views`, so this can be wrapped around a control where it is built.
	attach { |views, hint|
		views.asArray.do { |v|
			v !? {
				v.acceptsMouseOver = true;
				v.mouseOverAction = { |view, x, y| this.string_(hint.value(view, x, y)) };
			};
		};
		^views
	}

	string_ { |str| text !? { text.string = str ? defaultString } }

	string { ^text !? (_.string) }

	clear { this.string_(nil) }

	// kept here: a view in a window not yet shown answers false, and a
	// checkbox made then would start unticked
	visible { ^shown }

	visible_ { |bool|
		shown = bool;
		view.visible = bool;
		toggleBox !? { |b| b.value = bool };
	}

	// A checkbox that folds the bar away, for a window with somewhere to put
	// it. Made on the first ask and the same one after that. It has to sit
	// outside the bar, or folding it would take the way back with it.
	toggle {
		toggleBox ?? {
			toggleBox = CheckBox().string_("hints").value_(shown)
				.action_({ |v| shown = v.value; view.visible = v.value });
			this.attach(toggleBox, "this bar - untick to fold it away");
		};
		^toggleBox
	}
}
