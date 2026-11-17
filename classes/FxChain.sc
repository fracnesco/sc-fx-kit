// =====================================================================
// FxChain - a series of effects on a bus, in place, in order
//
// One group on the server, a list of FxUnits inside it, all working on
// the same bus one after another. The one everybody wants is the master
// chain - an eq and a compressor on the main out - and that is what
// ServerPanel opens:
//
//   c = FxChain.master;              // bus 0, every output channel
//   c.units;                         // -> [ParaEQ, Compressor], if installed
//   c.eq.preset = \warm;             // the first ParaEQ in the chain, if installed
//   c.eq.enabled = true;
//   c.compressor.threshold = -18;    // the first Compressor, if installed
//   c.gui;
//
// but any bus will do, and any effects:
//
//   c = FxChain(\verb, bus: 16, numChannels: 2);
//   c.add(ParaEQ());
//   c.add(Compressor());
//
// The group is built after the default group when the server comes up
// and again after cmd + period, so the chain hears everything that plays
// and whatever reads the bus at the tail - meters, a recorder, Insight -
// hears the chain. Effects that are off have no node; off costs nothing.
// The server's own volume synth sits before the chain, so the volume
// knob is a trim into it rather than a fader after it.
//
// The whole chain writes to one preset file and reads it back, effect
// by effect, so a master setting travels as one thing:
//
//   c.savePreset("tonight");         // -> <this repo>/presets/tonight.scd
//   c.loadPreset("tonight");
// =====================================================================

FxChain {
	classvar <all;

	var <name, <server, <bus, <numChannels;
	var <units, <group;
	var <enabled = true;
	var placementFunc;			// { [target, addAction] } for the group, at tree time
	var bootFunc, treeFunc, quitFunc, building = false;
	var <presetFile, <guiObj;
	var ids, usedIds;			// effect -> its id in this chain, and every id given so far
	var <owned = false;			// someone else places and rebuilds it - see *owned
	var <>onChange;				// { |chain| } - which effects are in, and on, changed
	var <>owner;				// what an owned chain belongs to, when it says: a rack

	*initClass { all = IdentityDictionary.new }

	// A chain under a name. Making another under a taken name frees the
	// old one first, so re-evaluating a setup file replaces the chain
	// rather than stacking a second group on the bus. `target` and
	// `addAction` say where the group goes; after the default group by
	// default, which is where a chain on an output bus belongs.
	*new { |name, bus = 0, numChannels = 2, server, target, addAction|
		^super.new.init(name, bus, numChannels, server, target, addAction)
	}

	// A chain that belongs to something else - a pad of a sampler, a module's
	// master, a branch of a rack. It keeps no place in the registry and no
	// hooks of its own: its owner says where its group goes, and when, with
	// `rebuild`, and hears through `onChange` when an effect is added, taken
	// out or switched, so it can route into the chain only while there is
	// something in it. Each effect in it has an id (see idOf) that a preset
	// keeps, so something can name an effect and still mean it after a reload.
	*owned { |name, numChannels = 2, server|
		^super.new.initOwned(name, numChannels, server)
	}

	*at { |name| ^all[name.asSymbol] }
	*names { ^all.keys.asArray.sort }
	*freeAll { all.values.copy.do(_.free) }

	// The chain on a server's main out: bus 0, one channel per output. Made
	// the first time it is asked for, with an eq and a compressor in it, both
	// off - each when its extension is installed (sc EQ, sc compressor). They
	// are looked up by name at runtime, so this compiles and runs without them.
	*master { |server|
		var key, chain;
		server = server ? Server.default;
		key = ("master_" ++ server.name).asSymbol;
		^all[key] ?? {
			chain = this.new(key, 0, server.options.numOutputBusChannels, server);
			[\ParaEQ, \Compressor].do { |name| name.asClass !? { |cls| chain.add(cls.new) } };
			chain
		}
	}

	init { |argName, argBus, argChannels, argServer, target, addAction|
		name = argName.asSymbol;
		server = argServer ? Server.default;
		bus = FxUnit.prBusIndex(argBus) ? 0;
		numChannels = (argChannels ? 2).asInteger.clip(1, 64);
		units = [];
		placementFunc = if (target.isNil) {
			{ [server.defaultGroup, \addAfter] }
		} {
			{ [target.value, addAction ? \addToTail] }
		};
		all[name] !? (_.free);
		all[name] = this;

		bootFunc = { this.prOnBoot };
		treeFunc = { this.prMakeNodes };		// also runs after cmd-period
		quitFunc = { this.prNodesGone };
		ServerBoot.add(bootFunc, server);
		ServerTree.add(treeFunc, server);
		ServerQuit.add(quitFunc, server);
		if (server.serverRunning) { this.prMakeNodes };
		^this
	}

	initOwned { |argName, argChannels, argServer|
		name = argName.asSymbol;
		server = argServer ? Server.default;
		bus = 0;
		numChannels = (argChannels ? 2).asInteger.clip(1, 64);
		units = [];
		owned = true;
		ids = IdentityDictionary.new;
		^this
	}

	// The owner's three messages. rebuild: the tree is there (again) - a new
	// group at `target`, on `bus` if one is given, and every effect that is on
	// gets its node. nodesGone: the server took them. freeNodes: take them
	// down, the chain is not wanted for now.
	rebuild { |target, addAction = \addToHead, argBus|
		argBus !? { this.bus_(argBus) };
		if (server.serverRunning.not or: { target.isNil }) { ^this };
		units.do(_.prNodeGone);
		group !? { |g| g.free };
		group = Group(target, addAction);
		units.do { |u| this.prSyncUnit(u) };
		this.changed(\nodes);
	}

	nodesGone { this.prNodesGone }

	freeNodes {
		units.do(_.prFreeNode);
		group !? { |g| if (server.serverRunning) { g.free } };
		group = nil;
		this.changed(\nodes);
	}

	// the bus it works on; the effects follow
	bus_ { |argBus|
		bus = FxUnit.prBusIndex(argBus) ? bus;
		units.do { |u| u.prChainMoved(this) };
	}

	server_ { |srv| server = srv; units.do { |u| u.prChainMoved(this) } }

	// an owned chain is as wide as its owner says; the effects follow at their next node
	numChannels_ { |n|
		n = (n ? 2).asInteger.clip(1, 64);
		if (n == numChannels) { ^this };
		numChannels = n;
		units.do { |u| u.prFreeNode; u.prChainMoved(this) };
		units.do { |u| this.prSyncUnit(u) };
	}

	// an effect may refuse a chain (a rack too deep in racks): it is not added
	prMayAdd { |unit|
		if (unit.tryPerform(\prMayJoin, this) == false) {
			("FxChain %: % cannot go in here - a rack holds racks one level deep, no more."
				.format(name, unit.class.name)).warn;
			^false
		};
		^true
	}

	// whether anything in it would do something now
	hasActiveUnits { ^enabled and: { units.any { |u| u.enabled } } }

	// an effect of this chain was switched on or off
	prUnitSwitched { |unit| onChange.value(this) }

	// ------------------------------------------------------------------ ids

	// What an effect is called in this chain: its kind and a number, \eq1,
	// \growl2. Given when it joins, kept in the chain's dict, never reused
	// while the chain lives.
	idOf { |unit| ^ids !? { ids[unit] } }

	unitAt { |id| ^ids !? { ids.findKeyForValue(id.asSymbol) } }

	prGiveId { |unit, wanted|
		var base, n = 1, id;
		ids = ids ?? { IdentityDictionary.new };
		usedIds = usedIds ? [];
		if (ids[unit].notNil) { ^ids[unit] };
		// an id a preset names is taken as it is, unless an effect here has it
		if (wanted.notNil and: { ids.includes(wanted.asSymbol).not }) {
			id = wanted.asSymbol;
		} {
			base = (unit.class.tryPerform(\title) ? unit.class.name).asString.toLower.select(_.isAlphaNum);
			while { id = (base ++ n).asSymbol; usedIds.includes(id) } { n = n + 1 };
		};
		if (usedIds.includes(id).not) { usedIds = usedIds.add(id) };
		ids[unit] = id;
		^id
	}

	free {
		guiObj !? (_.close);
		if (owned) {
			units.copy.do { |u| u.prLeaveChain };
			units = [];
			group !? { |g| if (server.serverRunning) { g.free } };
			group = nil;
			this.changed(\freed);
			^this
		};
		ServerBoot.remove(bootFunc, server);
		ServerTree.remove(treeFunc, server);
		ServerQuit.remove(quitFunc, server);
		units.copy.do { |u| u.prLeaveChain };
		units = [];
		group !? { |g| if (server.serverRunning) { g.free } };
		group = nil;
		if (all[name] === this) { all.removeAt(name) };
		this.changed(\freed);
	}

	// --------------------------------------------------------------- units

	// Put an effect in the chain, at the end or at `index`. It leaves
	// whatever it was doing before - a chain, a bus of its own - and takes
	// this chain's bus and channel count.
	add { |unit, index|
		if (unit.isNil) { ^this };
		if (units.includes(unit)) { ^this };
		if (this.prMayAdd(unit).not) { ^nil };
		unit.chain !? (_.remove(unit));
		unit.prJoinChain(this);
		this.prGiveId(unit);
		units = if (index.isNil) { units ++ [unit] } { units.insert(index, unit) };
		this.prSyncUnit(unit);
		this.changed(\units);
		onChange.value(this);
		^unit
	}

	remove { |unit|
		if (units.includes(unit).not) { ^this };
		units = units.copy;
		units.remove(unit);
		unit.prLeaveChain;
		ids !? { ids.removeAt(unit) };
		this.changed(\units);
		^unit
	}

	// the first effect of a class, or nil: FxChain.master.eq
	unitOfClass { |class| ^units.detect { |u| u.isKindOf(class) } }

	eq { ^\ParaEQ.asClass !? { |cls| this.unitOfClass(cls) } }
	compressor { ^\Compressor.asClass !? { |cls| this.unitOfClass(cls) } }

	at { |index| ^units[index] }
	size { ^units.size }

	// Move one effect to another position. The nodes are moved rather than
	// rebuilt, so nothing is interrupted.
	moveUnit { |from, to|
		var unit;
		from = from.asInteger;
		to = to.asInteger.clip(0, units.size - 1);
		if (from < 0 or: { from >= units.size } or: { from == to }) { ^this };
		units = units.copy;
		unit = units.removeAt(from);
		units = units.insert(to, unit);
		this.prOrderNodes;
		this.changed(\units);
		onChange.value(this);
	}

	// Whether the chain as a whole is in: off, every effect's node is faded
	// out and the bus is left alone, whatever each effect says for itself.
	enabled_ { |bool|
		bool = bool.booleanValue;
		if (bool == enabled) { ^this };
		enabled = bool;
		units.do { |u| this.prSyncUnit(u) };
		this.changed(\enabled);
		onChange.value(this);
	}

	// ------------------------------------------------------------ the nodes

	prOnBoot {
		// the boot resets nothing here - the tree hook builds the group -
		// but the defs are sent early so the first enable does not wait
		fork {
			units.collect(_.class).as(IdentitySet).do { |cls|
				cls.prEnsureDef(server, numChannels, nil);
			};
		};
	}

	// The tree was rebuilt, or the server has just come up: the group goes
	// back where it belongs, and every effect that is on gets its node.
	prMakeNodes {
		var place;
		if (server.serverRunning.not) { ^this };
		units.do(_.prNodeGone);
		place = placementFunc.value;
		group = Group(place[0], place[1]);
		units.do { |u| this.prSyncUnit(u) };
		this.changed(\nodes);
	}

	prNodesGone {
		group = nil;
		units.do(_.prNodeGone);
		this.changed(\nodes);
	}

	// Make one effect's node match what it says: there while it is on and
	// the chain is on and the server is up, gone otherwise. A node goes
	// after the node of the effect before it, so the order of `units` is
	// the order on the bus. The def goes first, and this comes back once
	// the server has it.
	prSyncUnit { |unit|
		var prev, tgt, act;
		if (units.includes(unit).not) { ^this };
		if (enabled.not or: { unit.enabled.not } or: { group.isNil }
			or: { server.serverRunning.not }) {
			^unit.prFreeNode
		};
		if (unit.synth.notNil) { ^this };
		if (unit.class.prEnsureDef(server, numChannels, { this.prSyncUnit(unit) }).not) {
			^this
		};
		prev = this.prPreviousNode(unit);
		if (prev.isNil) { tgt = group; act = \addToHead } { tgt = prev; act = \addAfter };
		unit.prMakeNode(tgt, act);
	}

	prPreviousNode { |unit|
		var i = units.indexOf(unit), found;
		if (i.isNil) { ^nil };
		units.keep(i).reverseDo { |u| if (found.isNil and: { u.synth.notNil }) { found = u.synth } };
		^found
	}

	// after a reorder: every node that exists, walked into order
	prOrderNodes {
		var prev;
		if (group.isNil) { ^this };
		units.do { |u|
			u.synth !? { |sy|
				if (prev.isNil) { sy.moveToHead(group) } { sy.moveAfter(prev) };
				prev = sy;
			};
		};
	}

	// the target and add action an analyser reading the whole chain wants
	analysisPlacement { ^group !? { [group, \addToTail] } }

	// ------------------------------------------------------------- presets

	// Where the chain's presets live: a folder next to the class files, so
	// they travel with the repository. Not committed - see .gitignore.
	*presetDir {
		^this.filenameSymbol.asString.dirname.dirname +/+ "presets"
	}

	*ensurePresetDir {
		var dir = this.presetDir;
		if (File.exists(dir).not) { File.mkdir(dir) };
		^dir
	}

	// A bare name lands in the preset folder and gets an .scd extension. An
	// existing file keeps whatever name it already has.
	*presetPath { |name|
		var p = name.asString;
		if (p.contains("/").not and: { p.contains("\\").not }) { p = this.presetDir +/+ p };
		p = p.standardizePath;
		if (PathName(p).extension.isEmpty and: { File.exists(p).not }) {
			p = p ++ ".scd";
		};
		^p
	}

	// every preset in the folder, newest file first
	*presets {
		var files = (this.presetDir +/+ "*.scd").pathMatch;
		^files.sort { |a, b| File.mtime(a) > File.mtime(b) }
	}

	*presetNames { ^this.presets.collect { |p| PathName(p).fileNameWithoutExtension } }

	// what a chain writes: one entry per effect, its class and its state,
	// so a preset can put the same chain back on another rig
	asDict {
		^(
			name: name, enabled: enabled,
			units: units.collect { |u| (class: u.class.name, id: this.idOf(u), state: u.asDict) }
		)
	}

	// The chain made to be exactly these entries, in this order: an effect
	// with an entry's id and class is kept and given its state, anything else
	// is made, and what has no entry goes. What an owned chain's owner loads.
	setUnits { |entries|
		var wanted = List.new, old = units.copy;
		entries.asArray.do { |entry|
			var cls = entry[\class].asSymbol.asClass, unit;
			if (cls.isNil) {
				("FxChain: % is not installed - skipping that effect".format(entry[\class])).warn;
			} {
				unit = entry[\id] !? { |id| this.unitAt(id) };
				if (unit.isNil or: { unit.class != cls } or: { wanted.includes(unit) }) {
					// another kind of effect under that id: it goes, the id is free
					if (unit.notNil and: { wanted.includes(unit).not }) {
						unit.prLeaveChain; ids.removeAt(unit); old.remove(unit);
					};
					unit = cls.new;
					if (this.prMayAdd(unit)) {
						unit.prJoinChain(this);
						this.prGiveId(unit, entry[\id]);
					} { unit = nil };
				};
				unit !? {
					wanted.add(unit);
					unit.fromDict(entry[\state]);
				};
			};
		};
		old.do { |u| if (wanted.includes(u).not) { u.prLeaveChain; ids.removeAt(u) } };
		units = wanted.asArray;
		units.do { |u| this.prSyncUnit(u) };
		this.prOrderNodes;
		this.changed(\units);
		onChange.value(this);
	}

	// Effect by effect, in order: an entry lands on the effect of the same
	// class at that position when there is one, on the first effect of that
	// class otherwise, and makes a new one when there is none and the class
	// is installed. A class that is not installed is skipped and named.
	fromDict { |d|
		var taken = IdentitySet.new;
		if (d.isNil) { ^this };
		d[\units].asArray.do { |entry, i|
			var cls = entry[\class].asSymbol.asClass;
			var unit;
			if (cls.isNil) {
				("FxChain: % is not installed - skipping that effect"
					.format(entry[\class])).warn;
			} {
				unit = units[i];
				if (unit.isNil or: { unit.isKindOf(cls).not } or: { taken.includes(unit) }) {
					unit = units.detect { |u| u.isKindOf(cls) and: { taken.includes(u).not } };
				};
				unit ?? { unit = this.add(cls.new) };
				taken.add(unit);
				unit.fromDict(entry[\state]);
			};
		};
		d[\enabled] !? { |v| this.enabled_(v) };
		this.changed(\presets);
	}

	savePreset { |path|
		FxChain.ensurePresetDir;
		path = FxChain.presetPath(path ?? { presetFile ? name });
		File.use(path, "w", { |f| f.write(this.asDict.asCompileString) });
		presetFile = path;
		this.changed(\presets);
		("FxChain: saved % to %".format(name, path)).postln;
		^path
	}

	loadPreset { |path|
		var data;
		if (path.isNil) { "FxChain: loadPreset needs the name of a preset".warn; ^this };
		path = FxChain.presetPath(path);
		if (File.exists(path).not) {
			("FxChain: no preset at %".format(path)).warn;
			^this
		};
		data = path.load;
		if (data.isNil) {
			("FxChain: could not read the preset at %".format(path)).warn;
			^this
		};
		this.fromDict(data);
		presetFile = path;
		^this
	}

	presetName { ^presetFile !? { |p| PathName(p).fileNameWithoutExtension } }

	// ----------------------------------------------------------------- gui

	// The window, made once and brought to the front after that. Both ways
	// round announce `\gui`, so a button somewhere else that opens this
	// window - ServerPanel's `fx` - knows whether it is up without having to
	// ask.
	gui { |x, y|
		if (guiObj.isNil) { guiObj = FxChainGUI(this, x, y) } { guiObj.front };
		this.changed(\gui);
		^guiObj
	}

	closeGui { guiObj !? (_.close) }

	prGuiClosed { guiObj = nil; this.changed(\gui) }

	panel { |parent| ^FxChainPanel(this, parent) }

	label { ^name.asString }

	printOn { |stream|
		stream << "FxChain(" << name.asCompileString << ", bus " << bus << ", "
			<< numChannels << " ch, " << units.size << " units)"
	}
}
