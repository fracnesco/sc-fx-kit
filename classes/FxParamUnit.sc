// =====================================================================
// FxParamUnit - an effect written as a table of its parameters
//
// ParaEQ and Compressor each write out their settings, presets, clipboard,
// meters and window by hand. This is the same FxUnit with all of that worked
// out from one list, so an effect is its DSP, its table and its display:
//
//   Fuzz : FxParamUnit {
//       *controlName { ^\fuzz }
//       *params {
//           ^[
//               this.knob(\drive, 0, 40, \lin, 12, "dB", "drive", "how hard it hits"),
//               this.menu(\curve, #[\soft, \hard], \soft, "curve", "which curve"),
//               this.knob(\mix, 0, 1, \lin, 1, "", "mix", "wet against dry", box: \pct)
//           ]
//       }
//       *process { |sig, p|
//           var g = p[\drive].dbamp;
//           ^Select.ar(p[\curve], [(sig * g).tanh, (sig * g).clip2(1)])
//       }
//   }
//
// and then it behaves like every effect of the family, in the three places
// an FxUnit runs:
//
//   Ndef(\pad, { Fuzz.ar(Saw.ar(110) ! 2, \fuzz) }).play;
//   f = Fuzz(Ndef(\pad), \fuzz);          // drives that graph's \fuzz control
//   f.drive = 24; f.curve = \hard;        // a parameter is a property
//   f.gui;
//
//   f = Fuzz.onBus(0, 2).enabled_(true);  // a node of its own, in place
//   FxChain.master.add(Fuzz());           // in a chain
//   ~sdg.addInsert(0, \fuzz);             // an insert in SuperDirtGUI's mixer
//
// What the table gives you: `set` and `get` by name, a property per parameter
// name one `rate` or `size`), `controls` in table order on one control, the
// settings as an Event, a preset store and a clipboard per class, the window.
//
// The table is what saved nodeMaps carry, by position: append to it, never
// reorder. The settings are by name, so presets do not mind.
//
// Around the DSP, *ar always adds the same few things: `mix` and `output`
// when the table has them, the `On` crossfade, meters while a window is open.
// An effect with a tail answers true to *hasTail: bypassed, it stops hearing
// its input and the tail rings out (in a graph or an insert - a node of its
// own, switched off, is freed in `*fade` seconds, as with every FxUnit).
//
// Nothing here uses LocalIn: a SynthDef can have only one, and two effects of
// the family must be able to share a graph. Feedback goes through FxLoop.
// =====================================================================

FxParamUnit : FxUnit {
	classvar tables, stores, clipboards;
	classvar idCounter = 0;
	classvar <>meterRate = 25;			// replies a second while a window is open

	var <values;						// key -> what the setter was given, constrained
	var <id, responder, meterOn = false;
	var <inLevel = 0, <outLevel = 0, <extras, <lastReply = 0;

	*initClass {
		tables = IdentityDictionary.new;
		stores = IdentityDictionary.new;
		clipboards = IdentityDictionary.new;
	}

	// ------------------------------------------------- what a subclass writes

	*controlName { ^this.name.asString.toLower.asSymbol }

	// the table: an Array of this.knob, this.menu, this.toggle
	*params { ^this.subclassResponsibility(thisMethod) }

	// The DSP, between what *ar does around it. sig is an Array of channels, p
	// the parameters by key, as control rate signals (a menu's is its index, a
	// toggle's 0 or 1). ctx is an Event: ctx[\on] is the bypass, already faded;
	// set ctx[\dry] to the dry signal when the wet is late, so the two line up
	// (it is then what bypass fades to), and ctx[\extras] to an Array of
	// signals a display wants - they arrive in the object's `extras`.
	// Answers the wet signal, an Array as wide as sig.
	*process { |sig, p, ctx| ^this.subclassResponsibility(thisMethod) }

	// [name, (a few settings), name, (a few settings)...] - what is not said
	// is the table's default
	*presetList { ^[\default, ()] }

	// whether it goes on sounding after its input has stopped
	*hasTail { ^false }

	// what the display row shows: a subclass of FxParamView, or nil
	*displayClass { ^nil }

	// more widgets for the panel's top row: { |panel| [views] }
	*panelExtras { |panel| ^[] }

	// where the window opens, and how big
	*windowSize { ^Point(860, 150 + (this.sections.size * 76) + if (this.displayClass.notNil) { 210 } { 0 }) }

	// what a window calls it
	*title { ^this.name.asString.toLower }

	// what SuperDirtGUI's insert menu calls it, and its kind there
	*insertKind { ^this.name.asString.toLower.asSymbol }
	*insertLabel { ^this.name.asString }

	// ---------------------------------------------------------- table entries

	// A knob and a box. box says what the box shows: \ms for seconds as
	// milliseconds, \pct for 0..1 as percent, nil for the value as it is.
	// lag is the control's smoothing: 0.05 unless said; 0 for things that move
	// in steps. section is the row it goes in; hidden ones (nil) have no widget.
	*knob { |key, lo, hi, warp, default, units, label, hint, box, lag, section = \main, dec, step = 0|
		^(kind: \knob, key: key, spec: ControlSpec(lo, hi, warp ? \lin, step, default, units ? ""),
			default: default, label: label ? key.asString, hint: hint ? "",
			box: box, lag: lag ? 0.05, section: section, dec: dec)
	}

	*menu { |key, items, default, label, hint, section = \main|
		^(kind: \menu, key: key, items: items, default: default ? items[0],
			label: label ? key.asString, hint: hint ? "", lag: 0, section: section)
	}

	*toggle { |key, default = false, label, hint, section = \main|
		^(kind: \toggle, key: key, default: default,
			label: label ? key.asString, hint: hint ? "", lag: 0, section: section)
	}

	// the two every effect ends on
	*mixParams { |section = \output, mix = 1|
		^[
			this.knob(\mix, 0, 1, \lin, mix, "", "mix", "how much of the effect goes out, against the dry signal",
				box: \pct, section: section),
			this.knob(\output, -36, 24, \lin, 0, "dB", "output", "the level of the whole thing, in dB",
				section: section, dec: 1)
		]
	}

	// ------------------------------------------------------------ what it read

	*table {
		^tables[this] ?? {
			var t = this.params.flat;
			tables[this] = t;
			// a real method wins over doesNotUnderstand, so the property would not work
			t.do { |e|
				if (this.findRespondingMethodFor(e[\key]).notNil) {
					("%: the parameter % has the name of a method, and % will not reach it: use set and get, or rename it"
						.format(this.name, e[\key].asCompileString, e[\key])).warn;
				};
			};
			t
		}
	}

	*paramNames { ^this.table.collect { |e| e[\key] } }

	*param { |key| ^this.table.detect { |e| e[\key] == key.asSymbol } }

	*specs {
		var d = IdentityDictionary.new;
		this.table.do { |e| e[\spec] !? { |s| d[e[\key]] = s } };
		^d
	}

	// the sections with widgets, in the order the table first says them
	*sections {
		var seen = [];
		this.table.do { |e|
			var s = e[\section];
			if (s.notNil and: { seen.includes(s).not }) { seen = seen.add(s) };
		};
		^seen
	}

	*prToControl { |e, v|
		^switch (e[\kind],
			\menu, { e[\items].indexOf(v) ? 0 },
			\toggle, { v.binaryValue },
			{ v }
		)
	}

	*prFromControl { |e, c|
		^switch (e[\kind],
			\menu, { e[\items][c.round.asInteger.clip(0, e[\items].size - 1)] },
			\toggle, { c > 0.5 },
			{ e[\spec].constrain(c) }
		)
	}

	// what a value means, whichever way it was said; nil when it means nothing
	*prConstrain { |e, v|
		^switch (e[\kind],
			\menu, {
				if (v.isNumber) {
					e[\items][v.round.asInteger.clip(0, e[\items].size - 1)]
				} {
					if (e[\items].includes(v.asSymbol)) { v.asSymbol } { nil }
				}
			},
			\toggle, { if (v.isNumber) { v > 0.5 } { v == true } },
			{ e[\spec].constrain(v) }
		)
	}

	*defaultControls { ^this.table.collect { |e| this.prToControl(e, e[\default]) } }

	// --------------------------------------------------------------- the DSP

	// The effect as a UGen. `name` is the control the settings arrive on, all
	// of them, one number each, and around it `name`On, `name`Id and `name`Meter.
	*ar { |in, name, bypass = true|
		var sig = in.asArray, p, on, ctx, wet, dry, out, mix, gain, trig;
		name = (name ? this.controlName).asSymbol;
		p = this.prReadControls(name);
		on = if (bypass) { Lag.kr(NamedControl.kr(this.prSub(name, "On"), 1), this.fade) } { DC.kr(1) };
		ctx = (on: on, name: name, numChannels: sig.size);
		wet = this.process(if (this.hasTail) { sig * on } { sig }, p, ctx).asArray;
		dry = (ctx[\dry] ? sig).asArray;
		wet = Sanitize.ar(wet);
		mix = p[\mix];
		out = case
		{ mix.isNil } { dry + ((wet - dry) * on) }
		{ this.hasTail } { (dry * (1 - (mix * on))) + (wet * mix) }
		{ dry + (((wet * mix) - (dry * mix)) * on) };
		p[\output] !? { |db|
			gain = db.dbamp;
			out = out * (1 + ((gain - 1) * on));
		};
		trig = Impulse.kr(meterRate) * NamedControl.kr(this.prSub(name, "Meter"), 0);
		SendReply.kr(trig, this.replyName, [
			Peak.ar(sig, trig).reduce(\max),
			Peak.ar(out, trig).reduce(\max)
		] ++ (ctx[\extras] ? []), NamedControl.kr(this.prSub(name, "Id"), -1));
		^if (in.isArray) { out } { out[0] }
	}

	*replyName { ^'/fxparam' }

	*prSub { |name, suffix| ^(name.asString ++ suffix).asSymbol }

	// one control of many numbers, a signal per key, each with its own lag
	*prReadControls { |name|
		var c = NamedControl.kr(name, this.defaultControls);
		var p = IdentityDictionary.new;
		this.table.do { |e, i|
			var lag = e[\lag] ? 0;
			p[e[\key]] = if (lag > 0) { Lag.kr(c[i], lag) } { c[i] };
		};
		^p
	}

	*defName { |numChannels| ^(this.name.asString.toLower ++ numChannels.asInteger).asSymbol }

	// in place on a bus
	*synthDef { |numChannels|
		var n = numChannels.asInteger;
		^SynthDef(this.defName(n), { |bus = 0, gate = 1, fade = 0.02|
			var sig = this.ar(In.ar(bus, n).asArray, this.controlName, false);
			var env = EnvGen.kr(Env.asr(fade, 1, fade), gate, doneAction: Done.freeSelf);
			XOut.ar(bus, env, sig);
		})
	}

	*guiClass { ^FxParamGUI }
	*panelClass { ^FxParamPanel }

	// ------------------------------------------------------------- the object

	prInitSettings {
		values = IdentityDictionary.new;
		extras = [];
		this.class.table.do { |e| values[e[\key]] = e[\default] };
		idCounter = idCounter + 1;
		id = idCounter;
	}

	set { |key, value|
		var e = this.class.param(key), v;
		if (e.isNil) {
			("%: no parameter called %".format(this.class.name, key.asCompileString)).warn;
			^this
		};
		v = this.class.prConstrain(e, value);
		if (v.isNil) {
			("%: % is one of %".format(this.class.name, key, e[\items])).warn;
			^this
		};
		values[e[\key]] = v;
		this.prPush;
		this.prChanged(e[\key]);
	}

	get { |key| ^values[key.asSymbol] }

	// a parameter is a property: unit.drive, unit.drive = 3
	doesNotUnderstand { |selector ...args|
		var key = selector.asGetter;
		if (values.notNil and: { values.includesKey(key) }) {
			if (selector.isSetter) { this.set(key, args[0]); ^this };
			^values[key]
		};
		^this.superPerformList(\doesNotUnderstand, selector, args)
	}

	// what the node is given, in table order
	controls {
		^this.class.table.collect { |e| this.class.prToControl(e, values[e[\key]]) }
	}

	prAcceptControls { |array|
		var a = array.asArray, t = this.class.table;
		if (a.size != t.size) { ^false };
		t.do { |e, i| values[e[\key]] = this.class.prFromControl(e, a[i]) };
		^true
	}

	prNodeArgs {
		^[FxParamUnit.prSub(this.class.controlName, "Id"), id,
			FxParamUnit.prSub(this.class.controlName, "Meter"), meterOn.binaryValue]
	}

	prPushAll {
		super.prPushAll;
		this.prPushMeta;
	}

	prPushMeta {
		var idName = FxParamUnit.prSub(controlName, "Id");
		var meterName = FxParamUnit.prSub(controlName, "Meter");
		synth !? { |sy| sy.set(idName, id, meterName, meterOn.binaryValue) };
		target !? { |t| t.set(idName, id, meterName, meterOn.binaryValue) };
	}

	// something a graph's trigger control hears, on whichever node runs it
	prTrigger { |suffix|
		var name = FxParamUnit.prSub(controlName, suffix);
		synth !? { |sy| sy.set(name, 1) };
		target !? { |t| t.set(name, 1) };
	}

	prMakeNode { |tgt, addAction|
		super.prMakeNode(tgt, addAction);
		this.prPushMeta;
		if (meterOn) { this.prListen };
		^synth
	}

	prJoinChain { |aChain|
		super.prJoinChain(aChain);
		if (meterOn) { this.prListen };
	}

	target_ { |node|
		super.target_(node);
		if (values.notNil) { this.prPushMeta };
		if (meterOn) { this.prListen };
	}

	// --------------------------------------------------------------- meters

	// Whether the node reports its levels. A window turns this on while it is
	// open; from code, `meter = true` and read the levels.
	meter_ { |bool|
		bool = bool == true;
		if (bool == meterOn) { ^this };
		meterOn = bool;
		this.prPushMeta;
		if (meterOn) { this.prListen } { this.prUnlisten };
	}

	meter { ^meterOn }

	isAlive { ^(Main.elapsedTime - lastReply) < 1.0 }

	prListen {
		var srv = server ? Server.default;
		this.prUnlisten;
		responder = OSCFunc({ |msg|
			inLevel = msg[3] ? 0;
			outLevel = msg[4] ? 0;
			extras = msg.drop(5);
			lastReply = Main.elapsedTime;
			this.changed(\levels);
		}, FxParamUnit.replyName, srv.addr, argTemplate: [nil, id]);
	}

	prUnlisten {
		responder !? (_.free);
		responder = nil;
	}

	free {
		this.prUnlisten;
		super.free;
	}

	// ------------------------------------------------------------- settings

	settings {
		var d = ();
		this.class.table.do { |e| d[e[\key]] = values[e[\key]] };
		^d
	}

	// what a preset says over the defaults, the way the setters would put it
	*fullSettings { |few|
		var d = ();
		this.table.do { |e|
			d[e[\key]] = this.prConstrain(e, few !? { few[e[\key]] } ? e[\default]);
		};
		^d
	}

	// keys it does not know are ignored, ones it does not say are left alone:
	// a preset saved before a parameter existed still loads
	settings_ { |d|
		if (d.isNil) { ^this };
		this.class.table.do { |e|
			d[e[\key]] !? { |v|
				this.class.prConstrain(e, v) !? { |c| values[e[\key]] = c };
			};
		};
		this.prPush;
		this.prChanged(\settings);
	}

	// ------------------------------------------------------------- presets

	*presetStore {
		^stores[this] ?? {
			var builtins = this.presetList.clump(2).collect { |pair|
				[pair[0], this.fullSettings(pair[1])]
			}.flatten(1);
			stores[this] = FxPresetStore(this, builtins,
				Platform.userAppSupportDir +/+ (this.name.asString.toLower ++ "-presets.scd"));
			stores[this]
		}
	}

	*presets { ^this.presetStore.builtins }
	*userPresets { ^this.presetStore.userPresets }
	*presetNames { ^this.presetStore.names }
	*presetSettings { |name| ^this.presetStore.at(name) }
	*isUserPreset { |name| ^this.presetStore.isUser(name) }
	*presetFile { ^this.presetStore.file }
	*presetFile_ { |path| this.presetStore.file = path }
	*addPreset { |name, settings| ^this.presetStore.add(name, settings) }
	*removePreset { |name| ^this.presetStore.remove(name) }
	*clearUserPresets { this.presetStore.clear }

	presetName { ^this.class.presetStore.detect(this.settings) }

	preset_ { |name|
		var d = this.class.presetSettings(name);
		if (d.isNil) {
			("%: no preset called %".format(this.class.name, name.asCompileString)).warn;
			^this
		};
		this.settings_(d);
	}

	saveAsPreset { |name| ^this.class.addPreset(name, this.settings) }

	// --------------------------------------------------- copy and paste

	*clipboard { ^clipboards[this] }
	*hasClipboard { ^clipboards[this].notNil }

	copySettings {
		clipboards[this.class] = this.settings;
		this.class.changed(\clipboard);
		^clipboards[this.class]
	}

	pasteSettings {
		clipboards[this.class] !? { |d| this.settings_(d) };
		^this
	}

	copyFrom { |other|
		other !? { this.settings_(other.settings) };
		^this
	}
}
