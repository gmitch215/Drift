module example.com/demo

go 1.22.1

toolchain go1.22.3

require github.com/spf13/cobra v1.8.1

require (
	golang.org/x/net v0.29.0
	golang.org/x/text v0.18.0 // indirect
	github.com/inconshreveable/mousetrap v1.1.0 // indirect
)

replace golang.org/x/text => golang.org/x/text v0.17.0
