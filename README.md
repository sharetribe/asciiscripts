# Helper functions to edit Asciinema recordings

## Usage

There's no CLI implemented. You should open a Clojure REPL and call functions from there.

## Step-by-step guide

1. Open your favorite text editor. Write all the commands there in advance. We're *not* going to type them to terminal, instead we're going to just *copy-paste* the commands
2. Install [asciinema](https://asciinema.org/docs/getting-started)
3. Start recording

    ```
    asciinema rec
    ```

4. Start copy-pasting the commands

    (Please note: If you're using iTerm2, you may need to turn off paste bracketing)

    One neat way is to use iTerm2 > Edit > Paste Special > Advanced Paste...

5. End recording (ctrl+d) and save it locally (ctrl+c)

    Note the location where the recording was saved. Something like `/var/folders/0p/vtdv4p053pd6gkx8d5p98h000000gn/T/tmpoig8qzvd-ascii.cast`

6. Copy the recording to `./gifs/...` from the temporary location

   ```
   mv /var/folders/0p/vtdv4p053pd6gkx8d5p98h000000gn/T/tmpoig8qzvd-ascii.cast ./gifs/my-gif/original.cast
   ```

7. In your Clojure editor, open the asciiscripts `core` namespace `asciiscripts/src/core.clj`

8. Connect to REPL

9. In the `(comment ,,,)` block, add a new section for your GIF, something like:

    ```clj
     (-> (read-cast "./gifs/my-gif/original.cast")
          (apply-ops [ ,,, ])

          (write-cast "./gifs/my-gif/improved.cast")
          #_(pp)
          )
    ```

10. Next, start working with the recording. What you want to do is:

  * Read the cast file (`read-cast` function), apply some edit operations
    (`apply-ops` function) and write the improved cast file (`write-cast`) to
    disk.
  * Play the improved cast file using `asciinema play` to see how it looks
  * Comment the `write-cast` function and instead use `pp` function to pretty
    print the content of the cast file to identify where to cut, where to add
    pauses and so on.
  * Cut unnecessary stuff from the beginning (`:cut-start` op)
  * Cut unnecessary stuff from the end (`:cut-end` op)
  * Split events (`:split` op) if you pasted a large chunk at once
  * Quantize pauses (`:quantize` op) to make too long pauses shorter and too
    short pauses longer
  * Manually add some pauses (`:pause` op) after each command to give the viewer
    time to read what happened. (And make sure you add pauses *after*
    `:quantize`, otherwise quantize will override them)
  * Resize (`:resize` op) the recording to certain width and height

11. When you're done editing, use [asciicast2git](https://github.com/asciinema/asciicast2gif) to convert `.cast` file to `.gif` file

    Example:

    ```bash
    asciicast2gif gifs/example/improved.cast gifs/example/output.gif
    ```

## Example

Check `core` namespace `(comment)` block for more examples.

Check also `gifs/example` for example input and output `.cast` files.

``` clojure
(-> "input.cast"
    read-cast
    (apply-ops [[:cut-start {:end [1477]}]

                ;; Strip undesired output from the recorded session
                [:str-replace {:match #"^\[90mundefined\[39m\r\n" :replacement ""}]

                ;; Split breaks events into sub events to mimic typing
                [:split {:start [1487] :end [2062] :d 0.025M}]
                [:split {:start [2072] :end [2770] :d 0.025M}]
                [:split {:start [2778] :end [2882] :d 0.025M}]
                [:split {:start [2891] :end [2950] :d 0.025M}]

                ;; quantize makes pauses uniform random
                [:quantize {:min 0.01M :max 0.1M}]

                ;; cut removes events in given range
                [:cut {:start [2717] :end [2719]}]

                ;; merge events together
                [:merge {:start [2148] :end [2566]}]

                ;; pause adds a pause before the given event
                [:pause {:id [2072] :d 0.8M}]
                [:pause {:id [2567 0] :d 2M}]
                [:pause {:id [2778] :d 0.8M}]
                [:pause {:id [2891] :d 0.8M}]
                [:pause {:id [2959] :d 2M}]

                [:cut-end {:start [2960]}]])

    #_(pp) ;; debugging
    (write-cast "output.cast")
    )
```

## Alternatives

* https://github.com/cirocosta/asciinema-edit
