package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"time"
)

type executionRequest struct {
	Language string `json:"language"`
	Code     string `json:"code"`
}

type executionResponse struct {
	Status          string `json:"status"`
	Stdout          string `json:"stdout"`
	Stderr          string `json:"stderr"`
	ExitCode        *int   `json:"exitCode"`
	ExecutionTimeMs int64  `json:"executionTimeMs"`
	Truncated       bool   `json:"truncated"`
}

type problemDetail struct {
	Title  string            `json:"title"`
	Detail string            `json:"detail"`
	Status int               `json:"status"`
	Errors map[string]string `json:"errors"`
}

func main() {
	server := flag.String("server", "http://localhost:8080", "base url of the execution server")
	lang := flag.String("lang", "", "language id: inferred from the file extension when empty")
	flag.Parse()

	// number of args should be exactly one (name of file)
	args := flag.Args()
	if len(args) != 1 {
		flag.Usage()
		os.Exit(2)
	}

	// exit if not able to read the file
	path := args[0]
	data, err := os.ReadFile(path)
	if err != nil {
		fmt.Fprintf(os.Stderr, "rcc: %v\n", err)
		os.Exit(1)
	}

	// infer the language source from the file extension if the language is not specified via -lang flag
	language := *lang
	if language == "" {
		switch filepath.Ext(path) {
		case ".py":
			language = "python"
		}
	}

	// exit if language flag is not provided and file extension is also not present
	if language == "" {
		fmt.Fprintf(os.Stderr, "rcc: cannot infer language from %q, pass -lang\n", path)
		os.Exit(2)
	}

	body, err := json.Marshal(executionRequest{Language: language, Code: string(data)})
	if err != nil {
		fmt.Fprintf(os.Stderr, "rcc: failed to marshal: %v\n", err)
		os.Exit(1)
	}

	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Post(*server+"/api/v1/executions", "application/json", bytes.NewReader(body))

	if err != nil {
		fmt.Fprintf(os.Stderr, "rcc: %v\n", err)
		os.Exit(1)
	}

	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		var problem problemDetail
		if err := json.NewDecoder(resp.Body).Decode(&problem); err != nil {
			fmt.Fprintf(os.Stderr, "rcc: server returned %s\n", resp.Status)
			os.Exit(1)
		}
		fmt.Fprintf(os.Stderr, "rcc: %s\n", problem.Detail)
		for field, message := range problem.Errors {
			fmt.Fprintf(os.Stderr, " %s: %s\n", field, message)
		}
		os.Exit(1)
	}

	var result executionResponse
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		fmt.Fprintf(os.Stderr, "rcc: cannot read server response %v\n", err)
		os.Exit(1)
	}

	// copy the stdout and stderr of the executed code to this cli's process's stdout and stderr
	fmt.Fprint(os.Stdout, result.Stdout)
	fmt.Fprint(os.Stderr, result.Stderr)

	if result.Truncated {
		fmt.Fprintln(os.Stderr, "rcc: output truncated at 64KB")
	}

	exit := "n/a"
	if result.ExitCode != nil {
		exit = strconv.Itoa(*result.ExitCode)
	}

	fmt.Fprintf(os.Stderr, "rcc: status=%s exit=%s time=%dms\n", result.Status, exit, result.ExecutionTimeMs)

}
