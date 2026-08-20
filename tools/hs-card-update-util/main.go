package main

import (
	"HSCardUtil/net"
	"HSCardUtil/progress"
	"database/sql"
	"encoding/json"
	"fmt"
	_ "github.com/mattn/go-sqlite3"
	"io"
	"log"
	"os"
	"strings"
)

// Card 是 HearthstoneJSON 卡牌对象在本地索引中需要保存的字段子集。
//
// mechanics、races 在上游 JSON 中是数组，写入 SQLite 前会被压缩成逗号分隔的文本；
// CardId 对应 JSON 的 id，也是 cards 表执行 INSERT OR REPLACE 时使用的业务唯一键。
type Card struct {
	Artist      string   `json:"artist"`
	Attack      int      `json:"attack"`
	Health      int      `json:"health"`
	CardClass   string   `json:"cardClass"`
	Cost        int      `json:"cost"`
	DbfId       int      `json:"dbfId"`
	Flavor      string   `json:"flavor"`
	CardId      string   `json:"id"` // 使用 CardId
	IsMiniSet   bool     `json:"isMiniSet"`
	Name        string   `json:"name"`
	Rarity      string   `json:"rarity"`
	CardSet     string   `json:"set"` // 更新为 cardSet
	SpellSchool string   `json:"spellSchool"`
	Text        string   `json:"text"`
	Type        string   `json:"type"`
	Mechanics   []string `json:"mechanics"`
	Race        string   `json:"race"`
	Races       []string `json:"races"`
}

// findFirst 返回第一个满足条件的元素；未找到时返回 T 的零值。
// 命令行解析依赖这一零值语义，把空字符串视为“未显式传参”。
func findFirst[T any](arr []T, f func(T) bool) T {
	var result T
	for _, v := range arr {
		if f(v) {
			result = v
			break
		}
	}
	return result
}

const (
	remoteUrlArg    = "--remoteURL="
	dbPathArg       = "--dbPath="
	proxyAddressArg = "--proxyAddress="
)

// main 解析三个可选参数，下载卡牌全集并刷新本地 SQLite 索引。
//
// 默认读取 HearthstoneJSON 最新简体中文数据。下载响应体的关闭权由 main 持有，
// updateDB 只消费流，不负责关闭网络连接。
func main() {
	args := os.Args

	var remoteUrl = findFirst(args, func(x string) bool {
		return strings.HasPrefix(x, remoteUrlArg)
	})
	if remoteUrl == "" {
		remoteUrl = "https://api.hearthstonejson.com/v1/latest/zhCN/cards.json"
	} else {
		remoteUrl = strings.Split(remoteUrl, remoteUrlArg)[1]
		if remoteUrl == "" {
			remoteUrl = "https://api.hearthstonejson.com/v1/latest/zhCN/cards.json"
		}
	}

	var dbPath = findFirst(args, func(x string) bool {
		return strings.HasPrefix(x, dbPathArg)
	})
	if dbPath == "" {
		dbPath = "hs_cards.db"
	} else {
		dbPath = strings.Split(dbPath, dbPathArg)[1]
		if dbPath == "" {
			dbPath = "hs_cards.db"
		}
	}

	var proxyAddress = findFirst(args, func(x string) bool {
		return strings.HasPrefix(x, proxyAddressArg)
	})
	if proxyAddress != "" {
		proxyAddress = strings.Split(proxyAddress, proxyAddressArg)[1]
	}

	log.Println("send request")
	jsonIO := net.GetJsonIO(proxyAddress, remoteUrl)

	if jsonIO == nil {
		return
	}

	defer jsonIO.Close()

	updateDB(dbPath, jsonIO)

}

// updateDB 以流式方式把 JSON 数组写入 cards 表。
//
// 使用 json.Decoder 而不是一次性反序列化整个文件，避免卡牌全集随版本增长后占用过多内存。
// 表以 cardId 为唯一键，因此重复执行会刷新已有卡牌并保留数据库文件本身。当前实现逐条提交，
// 任意一条解析或写入失败都会终止进程，调用者可以据此判断本次索引更新未完整结束。
func updateDB(dbPath string, jsonIO io.ReadCloser) {
	// sql.Open 只创建连接句柄；后续建表/Prepare 才会实际验证文件是否可访问。
	db, err := sql.Open("sqlite3", dbPath)
	if err != nil {
		log.Fatalf("Error opening SQLite database: %v", err)
	}
	defer func(db *sql.DB) {
		err := db.Close()
		if err != nil {
			log.Println("Error closing SQLite database")
		}
	}(db)

	// CREATE IF NOT EXISTS 允许首次创建和增量刷新共用同一条执行路径。
	createTableQuery := `CREATE TABLE IF NOT EXISTS cards (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		cardId TEXT UNIQUE,
		artist TEXT,
		attack INTEGER,
		health INTEGER,
		cardClass TEXT,
		cost INTEGER,
		dbfId INTEGER,
		flavor TEXT,
		isMiniSet BOOLEAN,
		name TEXT,
		rarity TEXT,
		cardSet TEXT,
		spellSchool TEXT,
		text TEXT,
		type TEXT,
		mechanics TEXT,
		race TEXT,
		races TEXT
	);`
	if _, err := db.Exec(createTableQuery); err != nil {
		log.Fatalf("Error creating table: %v", err)
	}

	// 复用预编译语句，避免为卡牌全集中的每个对象重复解析 SQL。
	insertOrReplaceQuery := `INSERT OR REPLACE INTO cards (cardId, artist, attack, health, cardClass, cost, dbfId, flavor, isMiniSet, name, rarity, cardSet, spellSchool, text, type, mechanics, race, races)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
	stmt, err := db.Prepare(insertOrReplaceQuery)
	if err != nil {
		log.Fatalf("Error preparing statement: %v, please try deleting the %v file", err, dbPath)
	}
	defer func(stmt *sql.Stmt) {
		err := stmt.Close()
		if err != nil {
			log.Println("Error closing statement")
		}
	}(stmt)

	decoder := json.NewDecoder(jsonIO)
	// 先消费数组起始标记 '['，随后 More 会逐个判断是否还有卡牌对象。
	if _, err := decoder.Token(); err != nil {
		log.Fatalf("Error reading JSON: %v", err)
	}

	index := 0
	go progress.PrintLoadingBar()
	for decoder.More() {
		var card Card
		if err := decoder.Decode(&card); err != nil {
			log.Fatalf("Error decoding JSON: %v", err)
		}

		// SQLite 表没有数组列；这里采用项目查询端约定的逗号分隔格式。
		mechanics := strings.Join(card.Mechanics, ",")
		races := strings.Join(card.Races, ",")

		if _, err := stmt.Exec(card.CardId, card.Artist, card.Attack, card.Health, card.CardClass, card.Cost, card.DbfId, card.Flavor, card.IsMiniSet, card.Name, card.Rarity, card.CardSet, card.SpellSchool, card.Text, card.Type, mechanics, card.Race, races); err != nil {
			log.Fatalf("Error inserting or replacing data: %v", err)
		}
		index++
		sprintf := fmt.Sprintf("deal index: %-6d, cardName: %-50s", index, card.Name)
		progress.SetPrintLoadingBarTip(sprintf)
	}
	progress.CancelPrintLoadingBar()

	// 消费数组结束标记 ']'，同时验证输入没有在最后一个对象后被截断。
	if _, err := decoder.Token(); err != nil {
		log.Fatalf("Error reading JSON: %v", err)
	}

	log.Println("\nData inserted or replaced successfully")
}
