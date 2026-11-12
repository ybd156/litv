// 临时验证程序：模拟一台电视连 WS 待命，把收到的遥控指令打印出来。
// 用法：go run wstv.go <ws-url> <token>
//
// 注意：不要用短的 ReadDeadline 循环——gorilla 把"读超时"视为连接已损坏，
// 之后任何读都会 panic("repeated read on failed websocket connection")。
// 正确做法是一个长 deadline 的单次读循环。
package main

import (
	"fmt"
	"os"
	"time"

	"github.com/gorilla/websocket"
)

func main() {
	url := os.Args[1]
	token := os.Args[2]

	h := map[string][]string{"Authorization": {"Bearer " + token}}
	c, _, err := websocket.DefaultDialer.Dial(url, h)
	if err != nil {
		fmt.Println("DIAL_FAIL", err)
		os.Exit(1)
	}
	defer c.Close()
	fmt.Println("CONNECTED")

	_ = c.SetReadDeadline(time.Now().Add(30 * time.Second))
	for {
		_, msg, err := c.ReadMessage()
		if err != nil {
			fmt.Println("CLOSED", err)
			return
		}
		fmt.Println("RECV", string(msg))
	}
}
