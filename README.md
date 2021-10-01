# Helper functions to edit Asciinema recordings

## Usage

There's no CLI implemented. You should open a Clojure REPL and call functions from there.

## Example

``` clojure
(-> "input.cast"
    read-cast
    (apply-ops [[:cut-start {:end [44]}]
                [:split {:start [45] :end [100] :d 0.035M}]
                [:split {:start [260] :end [286] :d 0.025M}]
                [:split {:start [299] :end [373] :d 0.025M}]
                [:split {:start [382] :end [484] :d 0.025M}]
                [:quantize {:min 0.01M :max 0.1M}]
                [:pause {:id [55], :d 0.5M}]
                [:pause {:id [79], :d 0.5M}]
                [:pause {:id [119], :d 0.5M}]

                [:pause {:id [260], :d 0.8M}]
                [:pause {:id [286], :d 0.8M}]
                [:pause {:id [299], :d 0.8M}]
                [:pause {:id [373], :d 0.8M}]
                [:pause {:id [382], :d 0.8M}]
                [:pause {:id [484], :d 0.8M}]

                [:pause {:id [493], :d 2M}]

                ])
    #_(pp) ;; debugging
    (write-cast "output.cast")
    )
```

## Convert to GIF

Use [asciicast2git](https://github.com/asciinema/asciicast2gif)

## Alternatives

* https://github.com/cirocosta/asciinema-edit
