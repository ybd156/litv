package main

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

// TestWritePortFileNextToConfig 锁住端口文件的位置与内容。
//
// 这个文件是管理台/脚本读取「实际监听端口」的唯一入口：
// 配置端口被占用时服务会自动顺延，不回写的话界面上的端口就是错的。
func TestWritePortFileNextToConfig(t *testing.T) {
	dir := t.TempDir()
	cfgPath := filepath.Join(dir, "config.yaml")

	writePortFile(cfgPath, 18080)

	got, err := os.ReadFile(filepath.Join(dir, "litv.port"))
	if err != nil {
		t.Fatalf("端口文件没有写出来: %v", err)
	}
	if strings.TrimSpace(string(got)) != strconv.Itoa(18080) {
		t.Fatalf("端口文件内容不对: %q", string(got))
	}
}

// TestWritePortFileEmptyPathIsNoop 说明为什么不能把原始 *cfgPath 传进来。
//
// 启动参数 -config 可以留空（此时靠 config.DiscoverPath 自动发现），
// 空串会让 writePortFile 静默什么都不做。调用方必须传解析后的路径。
func TestWritePortFileEmptyPathIsNoop(t *testing.T) {
	writePortFile("", 18080)
	if _, err := os.Stat("litv.port"); !os.IsNotExist(err) {
		t.Fatal("空路径不应该写端口文件（写到了当前目录）")
	}
}
