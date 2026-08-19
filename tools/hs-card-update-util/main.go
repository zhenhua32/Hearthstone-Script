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

// SUBSET_RULE
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

// Hearthstone\Data\Win\unity3d.unity3d
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

func updateDB(dbPath string, jsonIO io.ReadCloser) {
	// 打开 SQLite 数据库
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

	// 创建表
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

	// 准备插入或替换语句
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
	// Read the opening bracket
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

		// Convert slices to comma-separated strings for SQLite
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

	// Read the closing bracket
	if _, err := decoder.Token(); err != nil {
		log.Fatalf("Error reading JSON: %v", err)
	}

	log.Println("\nData inserted or replaced successfully")
}
