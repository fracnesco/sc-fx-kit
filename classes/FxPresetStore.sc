// =====================================================================
// FxPresetStore - named settings, the ones the library ships and yours
//
// Every effect class keeps one of these: a list of the presets it was
// written with, and after them the ones you have saved, which live in a
// file of your own between sessions. The store does not care what a
// preset holds - an array of eq bands, an Event of compressor settings -
// only that it can be written with asCompileString and read back.
//
//   store = FxPresetStore(ParaEQ, [\flat, bands, \warm, bands], path);
//   store.names;                 // the menu
//   store.at(\warm);             // a copy of one
//   store.add("my room", bands); // joins the menu, and the file
//   store.remove("my room");
//   store.detect(bands);         // the name these settings are, or nil
//
// A name the library ships is refused rather than shadowed: those are
// what every saved kit and chain is written against. Adding, removing
// and clearing announce `\presets` on the owning class, so every open
// window hears about it.
// =====================================================================

FxPresetStore {
	var <owner;				// the class that announces \presets
	var <builtins;			// name, data, name, data - in menu order
	var <userPresets;		// the same, for the ones you save yourself
	var <file;				// where those are kept between sessions
	var <>legacyFile;		// read once, if `file` does not exist yet
	var loaded = false;
	var blocked;			// files this session will not write: their only copy would go
	var lastFile;			// the last real file the presets were read from

	*new { |owner, builtins, file, legacyFile|
		^super.newCopyArgs(owner, builtins ? [], [], file, legacyFile)
	}

	// Another file: it is read the next time the presets are looked at,
	// and the ones of a file read before are left there. No file at all
	// keeps what is in memory, and so does coming back from it to the file
	// last read: the presets join what the file holds. Only a real file
	// other than the last one read starts from its own presets alone.
	file_ { |path|
		if (path == file) { ^this };
		if (path.notNil and: { lastFile.notNil } and: { path != lastFile }) { userPresets = [] };
		file = path;
		loaded = false;
	}

	// The ones the library ships, then the ones you have saved. Reading
	// them is what brings the saved ones in from disk, so nothing is read
	// at startup that may never be looked at.
	all {
		this.prEnsure;
		^builtins ++ userPresets
	}

	names { ^this.all.clump(2).collect(_.first) }

	// a copy of one preset's data, or nil
	at { |name|
		var all = this.all;
		var i = all.indexOf(name.asSymbol);
		^i !? { all[i + 1].deepCopy }
	}

	includes { |name| ^this.names.includes(name.asSymbol) }

	isBuiltin { |name| ^builtins.clump(2).any { |pair| pair[0] == name.asSymbol } }

	// whether a name is one of yours, and so can be written over or removed
	isUser { |name|
		this.prEnsure;
		^userPresets.clump(2).any { |pair| pair[0] == name.asSymbol }
	}

	// the preset some settings are, or nil once you have moved something
	detect { |data|
		var all = this.all;
		var i = all.clump(2).detectIndex { |pair| pair[1] == data };
		^i !? { all[i * 2] }
	}

	// Keep some settings under a name. Saving under a name you have used
	// before writes over it. Answers the name, or nil if it was refused.
	add { |name, data|
		var i;
		name = name.asString.stripWhiteSpace;
		if (name.isEmpty) {
			("%: a preset needs a name".format(owner.name)).warn;
			^nil
		};
		name = name.asSymbol;
		if (this.isBuiltin(name)) {
			("%: % is one of the built in presets - pick another name"
				.format(owner.name, name.asCompileString)).warn;
			^nil
		};
		if (data.isNil) {
			("%: nothing to keep under %".format(owner.name, name.asCompileString)).warn;
			^nil
		};
		this.prEnsure;
		data = data.deepCopy;
		i = userPresets.indexOf(name);
		if (i.notNil) { userPresets[i + 1] = data } {
			userPresets = userPresets ++ [name, data];
		};
		this.prWrite;
		owner.changed(\presets);
		^name
	}

	// Drop one of yours. The built in ones stay where they are.
	remove { |name|
		var i;
		this.prEnsure;
		name = name.asSymbol;
		i = userPresets.indexOf(name);
		if (i.isNil) {
			("%: % is not one of yours to remove"
				.format(owner.name, name.asCompileString)).warn;
			^nil
		};
		userPresets = userPresets.copy;
		userPresets.removeAt(i);		// the name
		userPresets.removeAt(i);		// and its data
		this.prWrite;
		owner.changed(\presets);
		^name
	}

	// Forget the lot, on disk as well.
	clear {
		userPresets = [];
		loaded = true;
		this.prWrite;
		owner.changed(\presets);
	}

	// ------------------------------------------------------------ the file

	prEnsure {
		var data, path, mine;
		if (loaded) { ^this };
		loaded = true;					// a file that will not read is not
		userPresets = userPresets ? [];	// read again every time it is asked
		mine = userPresets;				// kept before there was a file, if any
		file !? { lastFile = file; this.prRecover };
		path = file;
		if (path.isNil or: { File.exists(path).not }) {
			// the first session after a rename reads the old file once
			path = legacyFile;
			if (path.isNil or: { File.exists(path).not }) { ^this };
		};
		data = this.prRead(path);
		if (data.notNil) {
			userPresets = this.prMerge(data, mine);
			// kept under the new name, and with what was kept before it
			if (path != file or: { mine.notEmpty }) { this.prWrite };
		} {
			// the next save writes over `file`: keep a copy of what would not read
			if (path == file) {
				this.prSetAside(path) !? { |aside|
					("%: could not read the presets at % - a copy is at %"
						.format(owner.name, path, aside)).warn;
				};
			} {
				("%: could not read the presets at %".format(owner.name, path)).warn;
			};
		};
		^this
	}

	// what a file holds if it reads as presets, or nil
	prRead { |path|
		var data;
		if (path.isNil or: { File.exists(path).not }) { ^nil };
		data = try { path.load };
		^if (data.isArray and: { data.size.even }) { data } { nil }
	}

	// whether a file reads as presets, as many as were written
	prReadsBack { |path, count|
		var data = this.prRead(path);
		^data.notNil and: { data.size == count }
	}

	// the file's, then the ones kept in memory - those win a name both have
	prMerge { |data, mine|
		var out = data.copy;
		mine.clump(2).do { |pair|
			var i = out.indexOf(pair[0]);
			if (i.notNil) { out[i + 1] = pair[1] } { out = out ++ pair };
		};
		^out
	}

	// A write that stopped between taking the old file away and putting
	// the new one in place left the new one as `file`.tmp: it goes into
	// place. A file that reads is left alone (the next write replaces the
	// tmp). Nothing that would be the only good copy is ever deleted.
	prRecover {
		var tmp = file ++ ".tmp", ok;
		if (this.prRead(file).notNil or: { this.prRead(tmp).isNil }) { ^this };
		if (File.exists(file)) {
			if (this.prSetAside(file).isNil) { ^this };
			try { File.delete(file) };
		};
		ok = try { File.copy(tmp, file); this.prRead(file).notNil } { false };
		if (ok) {
			File.delete(tmp);
			("%: the presets at % were put back from %"
				.format(owner.name, file, tmp)).warn;
		} {
			this.prBlock(file, "could not put % back in place".format(tmp));
		};
	}

	// A copy beside the file, never over an earlier one: answers its path,
	// or nil if it could not be made - then that file is not written again
	// this session, since a write would take the only copy. The same text
	// set aside last time is not copied again.
	prSetAside { |path|
		var aside = path ++ ".unreadable", n = 1, newest, text;
		while { File.exists(aside) } {
			newest = aside;
			n = n + 1;
			aside = path ++ ".unreadable" ++ n;
		};
		text = try { File.readAllString(path) };
		if (newest.notNil and: { text.notNil }
			and: { (try { File.readAllString(newest) }) == text }) { ^newest };
		^try { File.copy(path, aside); aside } {
			this.prBlock(path, "could not keep a copy of it");
			nil
		}
	}

	prBlock { |path, why|
		if (this.prBlocked(path)) { ^this };
		blocked = blocked.add(path);
		("%: % (%) - your presets will not be written there this session"
			.format(owner.name, why, path)).warn;
	}

	prBlocked { |path| ^blocked.asArray.any { |p| p == path } }

	// Written to a file beside it first and read back; only then does the
	// old file go and the new one take its place (File.copy will not write
	// over a file), read back again, and the tmp go last. A write that
	// stops half way leaves a good tmp, which the next load puts back.
	prWrite {
		var text, tmp, count;
		if (file.isNil or: { this.prBlocked(file) }) { ^this };
		text = userPresets.asCompileString;
		count = userPresets.size;
		tmp = file ++ ".tmp";
		try {
			if (File.exists(tmp)) { File.delete(tmp) };
			File.use(tmp, "w", { |f| f.write(text) });
			if (this.prReadsBack(tmp, count).not) {
				Error("the written presets do not read back").throw
			};
			if (File.exists(file)) { File.delete(file) };
			File.copy(tmp, file);
			if (this.prReadsBack(file, count).not) {
				Error("the copy in place does not read back - % has them".format(tmp)).throw
			};
			File.delete(tmp);
		} { |err|
			("%: could not write the presets to % (%)"
				.format(owner.name, file, err.errorString)).warn;
		};
	}
}
