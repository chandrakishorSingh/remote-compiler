package main

import (
	"flag"
	"fmt"
	"os"
	"path/filepath"
)

func main() {
	server := flag.String("server", "http://localhost:8080", "base url of the execution server")
	lang := flag.String("lang", "", "language id: inferred from the file extension when empty")
	flag.Parse()

	args := flag.Args()
	if len(args) != 1 {
		flag.Usage()
		os.Exit(2)
	}

	path := args[0]
	data, err := os.ReadFile(path)
	if err != nil {
		fmt.Fprintf(os.Stderr, "rcc: %v\n", err)
		os.Exit(1)
	}

	language := *lang
	if language == "" {
		switch filepath.Ext(path) {
		case ".py":
			language = "python"
		}
	}

	if language == "" {
		fmt.Fprintf(os.Stderr, "rcc: cannot infer language from %q, pass -lang\n", path)
		os.Exit(2)
	}

	fmt.Printf("server=%s language=%s file=%s bytes=%d\n", *server, language, path, len(data))
}
