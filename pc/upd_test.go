//go:build !windows

package main

import "testing"

func TestNewer(t *testing.T) {
	cases := [][3]string{{"1.9.0", "1.8.0", "true"}, {"1.10.0", "1.9.9", "true"}, {"1.8.0", "1.8.0", "false"}, {"1.8", "1.8.0", "false"}, {"2.0", "1.99.1", "true"}, {"1.7.9", "1.8.0", "false"}}
	for _, c := range cases {
		if got := newer(c[0], c[1]); (got && c[2] != "true") || (!got && c[2] == "true") {
			t.Errorf("newer(%s,%s)=%v", c[0], c[1], got)
		}
	}
}
