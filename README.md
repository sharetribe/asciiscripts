# Helper functions to edit Asciinema recordings

## Usage

There's no CLI implemented. You should open a Clojure REPL and call functions from there.

## Example

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

## Convert to GIF

Use [asciicast2git](https://github.com/asciinema/asciicast2gif)

## Alternatives

* https://github.com/cirocosta/asciinema-edit
