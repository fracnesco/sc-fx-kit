// Symlink this repository into the SuperCollider extensions folder.
// Evaluate the block below, then recompile the class library
// (Language -> Recompile Class Library, or ctrl/cmd + shift + L).
//
// This is the route for a clone you want to keep editing. If you would
// rather have it installed and updated for you, do not use this file -
// install it as a quark instead, from a fresh sclang:
//
//   Quarks.install("https://github.com/fracnesco/sc-fx-kit.git");
//   thisProcess.recompile;
//
// One or the other, not both: two copies on the class path is a
// duplicate class error at compile time.
//
// ln -s is macOS and Linux. On Windows, use the quark, or copy this
// folder into Platform.userExtensionDir by hand - .openOS opens it.
//
// FxKit depends on nothing. Every effect of the family is built on it -
// ParaEQ (../sc EQ) included - and you will usually not run this one:
// each effect's install.scd links it.
//
// Until 2026-11 the framework (FxUnit, FxChain, the widgets...) lived in
// sc EQ. An sc EQ from before then next to this one holds the same classes
// again, and the class library will not compile: update it (git pull) too.

(
var link, src, parent;

src = PathName(thisProcess.nowExecutingPath).pathOnly;
if (src.endsWith("/")) { src = src.drop(-1) };			// ln -s dislikes it
parent = PathName(src).parentPath;

link = { |from, to, what, done|
	if (from.endsWith("/")) { from = from.drop(-1) };
	case
	{ File.exists(to) } {
		("FxKit: % is already installed at %.".format(what, to)).postln;
		done.value;
	}
	{ File.exists(from).not } {
		("FxKit: % is not installed, and there is no copy of it at %."
			"\n  The class library will not compile without it."
			"\n  Install it first, then run this again."
			.format(what, from)).warn;
	}
	{ true } {
		File.mkdir(Platform.userExtensionDir);
		("ln -s" + from.shellQuote + to.shellQuote).unixCmd({ |exit|
			if (exit == 0) {
				"FxKit: linked % -> %".format(what, to).postln;
				done.value;
			} {
				"FxKit: linking % failed with exit code %".format(what, exit).warn;
			};
		});
	};
};

// an sc EQ from before the move holds the framework too: say so before
// the compile does, in fewer words
[parent +/+ "sc EQ", Platform.userExtensionDir +/+ "ParaEQ"].do { |dir|
	if (File.exists(dir +/+ "classes/FxUnit.sc")) {
		("FxKit: % is an older sc EQ that still holds FxUnit and the rest of the"
			"\n  framework, which now lives here. Update it (git pull) or the class"
			"\n  library will not compile - every Fx class twice.".format(dir)).warn;
	};
};

link.value(src, Platform.userExtensionDir +/+ "FxKit", "FxKit", {
	"".postln;
	"Now recompile the class library: ctrl/cmd + shift + L".postln;
});
)


// ---------------------------------------------------------------- verify
(
var missing = [\FxUnit, \FxChain, \FxRack, \FxStrip, \FxKnob, \FxPalette, \FxPresetStore, \FxHintBar,
	\FxParamUnit, \FxParamPanel, \FxParamGUI, \FxParamView, \FxLevelBars, \FxLoop, \FxLfo, \FxKit, \FxCubeWarp]
	.select { |sym| sym.asClass.isNil };

if (missing.notEmpty) {
	("FxKit is not compiled - % is missing."
		"\n  Recompile the class library, ctrl/cmd + shift + L, and check the"
		"\n  post window for compile errors - duplicate Fx classes mean an old sc EQ"
		"\n  is installed too: update it."
		.format(missing.join(", "))).warn;
} {
	"FxKit is installed.".postln;
	"  effects built on it: %".format(FxUnit.allSubclasses.collect(_.name).join(", ")).postln;
	"  a chain:  FxChain.master.gui        a table effect:  FxParamUnit, then cmd+d".postln;
	"  help:     FxUnit, then cmd+d".postln;
};
)


// ------------------------------------------------------------- uninstall
(
var link = Platform.userExtensionDir +/+ "FxKit";
case
{ File.type(link) == \symlink } {
	File.delete(link);
	("Removed %."
		"\n  Recompile the class library to finish.".format(link)).postln;
}
{ File.type(link) == \not_found } {
	"Nothing to remove at %.".format(link).postln;
}
{ true } {
	"% is not a link, leaving it alone.".format(link).warn;
};
)
