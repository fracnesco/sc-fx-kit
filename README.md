# FxKit

**What every effect of the family is built on: an effect that runs in a graph, on a bus or in a chain, chains and racks of effects, the knobs, cards and window they are drawn with, and the quick way to write a new one, as a table of its parameters.** A SuperCollider extension.

Put this in a `.sc` file in your extensions folder and recompile, and you have an effect with presets, a clipboard, meters and a window ([examples/fuzz-class.txt](examples/fuzz-class.txt)):

```
Fuzz : FxParamUnit {
	*controlName { ^\fuzz }
	*params {
		^[
			this.knob(\drive, 0, 40, \lin, 12, "dB", "drive", "how hard it hits", section: \main),
			this.menu(\curve, #[\soft, \hard], \soft, "curve", "which curve", \main),
			this.mixParams(\main)
		]
	}
	*presetList { ^[\default, (), \hot, (drive: 36, curve: \hard)] }
	*process { |sig, p, ctx|
		var g = p[\drive].dbamp;
		^Select.ar(p[\curve], [(sig * g).tanh, (sig * g).clip2(1)])
	}
}
```

```supercollider
Ndef(\pad, { Fuzz.ar(Saw.ar(110) ! 2 * 0.2, \fuzz) }).play;
f = Fuzz(Ndef(\pad), \fuzz);
f.drive = 24; f.curve = \hard;
f.gui;
```

These effects are built on it:

| | |
|---|---|
| eq | [ParaEQ](https://github.com/fracnesco/sc-eq) |
| dynamics | [Compressor](https://github.com/fracnesco/sc-compressor), [Shear and Wall](https://github.com/fracnesco/sc-peaks) |
| distortion | [Growl](https://github.com/fracnesco/sc-growl) |
| delay, repeat | [Tunnel](https://github.com/fracnesco/sc-tunnel), [Stammer](https://github.com/fracnesco/sc-stammer) |
| reverb | [Hall, Plate, Coil, Shimmer](https://github.com/fracnesco/sc-reverbs) |
| modulation, pitch | [Swirl](https://github.com/fracnesco/sc-swirl), [Spiral](https://github.com/fracnesco/sc-spiral) |
| spectral, granular, resonant | [Smear](https://github.com/fracnesco/sc-smear), [Cloud](https://github.com/fracnesco/sc-cloud), [Sympathy](https://github.com/fracnesco/sc-sympathy) |
| stereo | [Imager](https://github.com/fracnesco/sc-imager) |

[Insight](https://github.com/fracnesco/supercollider-insight-meter), the loudness meter, is not an effect but is drawn in the same window frame. [SuperDirtGUI](https://github.com/fracnesco/superdirt-gui) puts any of them in its mixer as an insert.

It depends on nothing. [BusManager](https://github.com/fracnesco/sc-bus-manager) is optional and lets a bus be named; without it a bus is an index or a `Bus`.

Written for SuperCollider 3.13 on macOS. Nothing in it is platform specific apart from the symlink in `install.scd`, which Linux shares and Windows does not.

## Install

Each effect's `install.scd` and quark manifest bring this with them. On its own:

```supercollider
Quarks.install("https://github.com/fracnesco/sc-fx-kit.git");
thisProcess.recompile;
```

or, from a clone in a folder called `sc fx kit`, evaluate the first block of `install.scd` and recompile. On Windows there is no `ln -s`, so use the quark.

Install it one way or the other, **not both**: two copies on the class path is a duplicate class error at compile time.

Until November 2026 the first half of this, `FxUnit` and everything around it, lived in [sc EQ](https://github.com/fracnesco/sc-eq). Update the two together: an sc EQ from before then next to this one holds the same classes a second time, and the class library will not compile. Both installers say so when they find an old one. The class names did not change, so nothing written against them has to.

### Check that it took

```supercollider
FxUnit.allSubclasses;            // every effect installed
FxChain.master.gui;              // the chain on the main out
```

## What an effect is

`FxUnit` is two things that agree with each other: a piece of DSP written as a pseudo UGen that reads named controls, and an object in the language that holds the settings and pushes them at whatever node runs that DSP. The same object works in three places:

```supercollider
// in a graph of your own - an Ndef, a SynthDef - as a UGen
Ndef(\pad, { Growl.ar(Saw.ar([110, 110.7]) * 0.2, \growl) }).play;
g = Growl(Ndef(\pad), \growl);   // drives that graph's \growl control

// on a bus, in place, with a synth of its own
g = Growl.onBus(16, 2);          // a Bus, an index, or a BusManager name
g.enabled = true;                // the node is built
g.enabled = false;               // and faded out - off costs nothing

// in a chain of effects on a bus - see below
FxChain.master.add(Growl());
```

Whichever way it runs, it has the same settings, presets, clipboard and window. `enabled` means the same thing in every one of them: with a node of its own the node exists exactly while the effect is on; driving a graph, an `On` control crossfades it to dry. That is the one thing this extension has to get right, and everything else, the chains and racks below and every effect above, is built on it.

Evaluating the same `onBus` line again replaces the effect it made before, as an Ndef does; it does not add a second one. One that is on comes back after a cmd-period or a reboot until it is freed, so keep it in a variable.

Every effect also lists its parameters the same way (`*mapParams`, `mapGet`, `mapSet`), so a controller, a macro or a small card can move any of them without knowing which effect it is. And it says how late it is (`latency`): a lookahead or an fft window, never the delay it is there to make.

## An effect from a table

ParaEQ and Compressor are written out by hand. `FxParamUnit` works all of that out from a table, so an effect is its DSP, its table and its display. What you get from the table:

- A property per parameter: `f.drive`, `f.drive = 24`, and `f.set(\drive, 24)`, `f.get(\drive)` by name. It goes through `doesNotUnderstand`, so a real method of the same name wins: never name a parameter `rate`, `size`, `shape` or `freeze`. The last makes the object immutable. A table with such a name warns when it is first read.
- All three places an `FxUnit` runs, and the inserts of SuperDirtGUI, which finds it by its class.
- A preset store per class and a clipboard per class.
- Meters, while a window is open: `f.inLevel`, `f.outLevel`, and whatever `ctx[\extras]` carries in `f.extras`.
- `FxParamPanel` and `FxParamGUI`: a row of knobs per section, and the hint on every one of them. `*displayClass` adds a view of your own, a subclass of `FxParamView`.
- `FxDevicePanel` (with `*panelClass { ^FxDevicePanel }` and `*guiClass { ^FxDeviceGUI }`): the same table as a device - header, display and meters, cards on pages from `*devicePages`, dimmed controls from `*isActive`, the family's colour from `*identityColour`. Growl is the example.

The table's order is the one thing to get right. It is what saved node maps carry, by position: append to it, never reorder.

Around `*process`, `*ar` adds `mix` and `output` when the table has them, the `On` crossfade, and the meters. An effect with a tail answers true to `*hasTail`: bypassed, it stops hearing its input and the tail rings out. A node of its own, switched off, is freed in `*fade` seconds, as with every `FxUnit`, tail or no tail.

## A chain of effects on a bus

`FxChain` is a group on the server with a list of effects inside it, all working on one bus one after another. The one everybody wants is the master chain:

```supercollider
c = FxChain.master;              // bus 0, one channel per output
c.units;                         // -> [ParaEQ, Compressor], each when it is installed
c.eq.enabled = true;             // nil without sc EQ
c.compressor.threshold = -18;    // nil without sc compressor
c.gui;
```

and any bus will do, with any effects, in any order:

```supercollider
c = FxChain(\verb, bus: 16, numChannels: 2);
c.add(Growl.new);
c.add(Hall.new);
c.moveUnit(1, 0);
c.remove(c.units[0]);
c.enabled = false;               // the whole chain out, whatever each effect says
```

`c.gui` shows one effect at a time: a tab each, in the order the sound goes through them, and under the tabs the effect's own panel. `FxStrip` shows a chain as a row of small cards instead, left to right, each with the knobs that say what the effect is doing.

The group is built after the default group when the server comes up and again after cmd + period, so the chain hears everything that plays, and whatever reads the bus at the tail (meters, a recorder, Insight) hears the chain. An effect that is off has no node.

A chain writes to one preset file and reads it back effect by effect, so a master setting travels as one thing:

```supercollider
c.savePreset("tonight");         // -> <this repo>/presets/tonight.scd
c.loadPreset("tonight");
FxChain.presetNames;
```

The folder is ignored by git, so updating the extension never touches your presets. Before the move these lived in sc EQ's `presets` folder; copy any you had across.

## Chains side by side: a rack

A rack is an effect whose inside is several chains in parallel. What comes in goes to every chain; what comes out of them is added up. It goes in a chain like any other effect and is saved with it.

```supercollider
r = FxChain.master.add(FxRack());        // one chain to begin with: nothing changes
r.branches[0].name = "squash";
r.branches[0].chain.add(Compressor()).enabled = true;
b = r.addBranch("dry");                  // a second chain, empty: the dry sound
b.level = -6;                            // dB
r.enabled = true;
```

An empty chain passes the sound, so every chain you add adds that much level until something is in it or its level comes down.

**The chains are lined up in time, and that is the one thing a rack has to get right.** A compressor with lookahead, a limiter or an fft cannot help being late; each says by how much (`latency`), and the rack holds every other chain back by the difference. Without that a dry chain beside a late one combs. Measured: a compressor that only delays, against a dry chain turned upside down, leaves digital silence at every lookahead tried.

```supercollider
r.latency;                 // the rack is as late as its latest chain, in seconds
b.heldBack;                // how long this chain waits for the others
b.align = false;           // this chain out of it: not held back, nothing waits for it
r.live = true;             // nothing held back at all: least latency, out of line
```

When a latency changes the rack waits a quarter of a second for it to settle, then crossfades to the new delays. A rack may sit in a chain of another rack, and the outer one lines it up too; no deeper than that.

In a strip of cards a rack is a bracket: its chains as a list with level, align, mute and solo, and beside it the effects of the chain you click. `r.gui` shows every chain with its effects.

## The window

Every effect window is the same frame: the power button, name and presets along the top, a display with the meters at its right, the controls in titled cards, mix and output closing them, and a bar along the bottom that says what the pointer is over. Each effect gives the display its family's face. The pieces are here for an effect written by hand: `FxHeader`, `FxKnob`, `FxSwitch`, `FxChoice`, `FxPad`, `FxCard`, `FxMeter` and `FxDeviceGUI`, in the colours and type of `FxPalette`.

On every knob: drag up and down, shift for fine, the wheel steps, a double click goes back to the default, a right click opens its menu. Click a knob, or tab to it, and the arrow keys move it by the amount that parameter is usually moved by: a dB for a level, a semitone for a frequency, a percent for a mix.

`ParaEQSpectrum` draws what an effect is working on behind its display; it costs nothing unless a window is showing it, and `ParaEQSpectrum.on = false` turns it off everywhere. It kept its name from when it was only the eq's.

## Feedback without LocalIn

A SynthDef can have one `LocalIn`, so two effects using it could not share a graph. `FxLoop` is a delay line on a `LocalBuf`, read and written with a phasor. Its shortest delay is a block and a few samples, and it costs 4 bytes a frame of the server's real time memory.

## Server restarts

An effect with a node of its own, and every chain, registers with `ServerBoot`, `ServerTree` and `ServerQuit`: the defs go up on boot, the nodes are rebuilt with the tree after cmd + period, and nothing is talked to after a quit. An effect driving an Ndef leaves all of that to the Ndef.

## Files

| file | what it holds |
|---|---|
| [classes/FxUnit.sc](classes/FxUnit.sc) | what an effect is: driving a node, owning a node, joining a chain, its parameters as a list, its latency |
| [classes/FxChain.sc](classes/FxChain.sc) | a series of effects on a bus, the master chain, chain presets, server lifecycle |
| [classes/FxRack.sc](classes/FxRack.sc) | `FxRack` and `FxRackBranch`: chains in parallel inside a chain, lined up by their latency |
| [classes/FxRackGUI.sc](classes/FxRackGUI.sc) | `FxRackCard`, a rack as a bracket in a strip, and `FxRackPanel`, a rack in a window |
| [classes/FxStrip.sc](classes/FxStrip.sc) | `FxStrip`, a chain as a strip of small cards, `FxDeviceCard`, `FxMapControls` |
| [classes/FxChainGUI.sc](classes/FxChainGUI.sc) | `FxChainPanel` and `FxChainGUI`: a tab per effect, the one shown with its own panel |
| [classes/FxParamUnit.sc](classes/FxParamUnit.sc) | an effect as a table: `*ar`, the object, presets, the clipboard |
| [classes/FxDevicePanel.sc](classes/FxDevicePanel.sc) | `FxDevicePanel`, the device frame with the effect family's face |
| [classes/FxParamGUI.sc](classes/FxParamGUI.sc) | `FxParamPanel` and `FxParamGUI`, worked out from the table |
| [classes/FxParamView.sc](classes/FxParamView.sc) | `FxParamView`, the base of a display, and `FxLevelBars` |
| [classes/FxDeviceGUI.sc](classes/FxDeviceGUI.sc) | the window every effect opens in: its panel and the one-line footer |
| [classes/FxWidgets.sc](classes/FxWidgets.sc) | the controls every effect window is built from: knob, choice, switch, pad, power, button, card, meter, header |
| [classes/FxPalette.sc](classes/FxPalette.sc) | the colours and fonts, values as words with their units, and each parameter's interval |
| [classes/FxPresetStore.sc](classes/FxPresetStore.sc) | named settings: the ones an effect ships and yours, on disk |
| [classes/FxHintBar.sc](classes/FxHintBar.sc) | the bar that says what the pointer is over |
| [classes/ParaEQSpectrum.sc](classes/ParaEQSpectrum.sc) | what an effect is working on: one FFT, read back a frame at a time |
| [classes/FxLoop.sc](classes/FxLoop.sc) | `FxLoop`, `FxLfo`, `FxKit`, `FxCubeWarp` |
| [HelpSource/Classes/](HelpSource/Classes/) | the help files, cmd/ctrl + d on a class name |
| [examples/fuzz-class.txt](examples/fuzz-class.txt) | the smallest effect, to copy |
| [presets/](presets/) | your chain presets, ignored by git |
| [install.scd](install.scd) | the symlink, a check, an uninstall |
| [sc-fx-kit.quark](sc-fx-kit.quark) | the quark manifest |

## Issues

Bug reports and questions are welcome in [Issues](https://github.com/fracnesco/sc-fx-kit/issues). If something misbehaves, the post window usually has the half of the story I need.

## License

MIT - see [LICENSE](LICENSE).
