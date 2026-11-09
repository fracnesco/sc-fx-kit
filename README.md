# FxKit

**An effect written as a table of its parameters: settings, presets, a clipboard, meters and a window, all from one list.** A SuperCollider extension.

ParaEQ and Compressor write all of that out by hand. This is the same `FxUnit`, worked out from a table, so that an effect is its DSP, its table and its display. Growl, Tunnel, Smear, Cloud, Stammer, Sympathy and Spiral are built on it. Put this in a `.sc` file in your extensions folder and recompile, and you have an effect ([examples/fuzz-class.txt](examples/fuzz-class.txt)):

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

It is built on [ParaEQ](../sc%20EQ), for `FxUnit`, the preset store, the palette and the hint bar. It depends on nothing else.

Written for SuperCollider 3.13 on macOS. Nothing in it is platform specific apart from the symlink in `install.scd`, which Linux shares and Windows does not.

## Install

Each effect's `install.scd` and quark manifest bring this with them. On its own:

```supercollider
Quarks.install("https://github.com/fracnesco/sc-fx-kit.git");
thisProcess.recompile;
```

or, from a clone in a folder called `sc fx kit` next to `sc EQ`, evaluate the first block of `install.scd` and recompile. On Windows there is no `ln -s`, so use the quark.

Install it one way or the other, **not both**: two copies on the class path is a duplicate class error at compile time.

### Check that it took

```supercollider
FxParamUnit.allSubclasses;       // the effects built on it
```

## What you get from the table

- A property per parameter: `f.drive`, `f.drive = 24`, and `f.set(\drive, 24)`, `f.get(\drive)` by name. It goes through `doesNotUnderstand`, so a real method of the same name wins: never name a parameter `rate`, `size`, `shape` or `freeze`. The last makes the object immutable. A table with such a name warns when it is first read.
- All three places an `FxUnit` runs: in a graph with `Fuzz.ar`, on a bus with `Fuzz.onBus`, in an `FxChain`. If SuperDirtGUI is installed it is an insert too, found by being a subclass of this.
- A preset store per class and a clipboard per class.
- Meters, while a window is open: `f.inLevel`, `f.outLevel`, and whatever `ctx[\extras]` carries in `f.extras`.
- `FxParamPanel` and `FxParamGUI`: a row of knobs per section, and the hint on every one of them. `*displayClass` adds a view of your own, a subclass of `FxParamView`.
- `FxDevicePanel` (with `*panelClass { ^FxDevicePanel }` and `*guiClass { ^FxDeviceGUI }`): the same table as a device - header, display and meters, cards on pages from `*devicePages`, dimmed controls from `*isActive`, the family's colour from `*identityColour`. Growl is the example.

The table's order is the one thing to get right. It is what saved node maps carry, by position: append to it, never reorder.

Around `*process`, `*ar` adds `mix` and `output` when the table has them, the `On` crossfade, and the meters. An effect with a tail answers true to `*hasTail`: bypassed, it stops hearing its input and the tail rings out. A node of its own, switched off, is freed in `*fade` seconds, as with every `FxUnit`, tail or no tail.

## Feedback without LocalIn

A SynthDef can have one `LocalIn`, so two effects using it could not share a graph. `FxLoop` is a delay line on a `LocalBuf`, read and written with a phasor. Its shortest delay is a block and a few samples, and it costs 4 bytes a frame of the server's real time memory.

## Files

| file | what it is |
|---|---|
| [classes/FxParamUnit.sc](classes/FxParamUnit.sc) | the base class: the table, `*ar`, the object, presets, the clipboard |
| [classes/FxDevicePanel.sc](classes/FxDevicePanel.sc) | `FxDevicePanel`, the device frame with the effect family's face (its window is `FxDeviceGUI`, in sc EQ) |
| [classes/FxParamGUI.sc](classes/FxParamGUI.sc) | `FxParamPanel` and `FxParamGUI`, worked out from the table |
| [classes/FxParamView.sc](classes/FxParamView.sc) | `FxParamView`, the base of a display, and `FxLevelBars` |
| [classes/FxLoop.sc](classes/FxLoop.sc) | `FxLoop`, `FxLfo`, `FxKit`, `FxCubeWarp` |
| [examples/fuzz-class.txt](examples/fuzz-class.txt) | the smallest effect, to copy |
| [install.scd](install.scd) | the symlink, a check, an uninstall |
| [sc-fx-kit.quark](sc-fx-kit.quark) | the quark manifest |

## License

MIT - see [LICENSE](LICENSE).
