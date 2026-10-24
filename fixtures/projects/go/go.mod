module example.com/demo

go 1.22.1

toolchain go1.27.3

require github.com/spf13/cobra v1.10.2

require (
	golang.org/x/net v0.61.0
	golang.org/x/text v0.18.0 // indirect
	github.com/inconshreveable/mousetrap v1.1.0 // indirect
)

replace golang.org/x/text => golang.org/x/text v0.43.0
