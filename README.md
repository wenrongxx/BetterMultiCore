# BetterMultiCore

Minecraft **Leaves / Paper 1.21.x** 鎻掍欢锛氬湪**涓嶄慨鏀瑰師鐗堣涔夈€佷笉鍏抽棴瀹炰綋 AI** 鐨勫墠鎻愪笅锛屾妸鍙苟琛岀殑宸ヤ綔鎽婂埌鍏朵粬 CPU 鏍革紝闄嶄綆涓荤嚎绋嬪帇鍔涖€?
- **澶氭牳鍖哄潡棰勭儹**锛坧reload / pathwarm锛夆€?瑙勫垝涓庡紓姝ュ姞杞借蛋 worker 绾跨▼姹? 
- **`/particle` 鍙戦€佹墖鍑?* 鈥?澶氳浼楁椂骞惰 `send`锛堝弬鏁般€乿iewers銆乫orce銆佹垚璐ユ枃妗堜笌鍘熺増涓€鑷达級  
- **楂樿礋杞?spread** 鈥?鍙缉灏忎富绾跨▼鎵归噺 / 鎷夐暱鏈彃浠舵壂鎻忛棿闅旓紝**涓嶆殏鍋滄ā鍧?*  
- **PlugMan 鐑嵏杞?* 鈥?`onDisable` 杩樺師鍘熺増 particle 鍛戒护鏍戯紝閬垮厤 ClassLoader 娉勬紡  

> **涓嶅仛鐨勪簨**锛氫笉 `setAware`/`setAI` 浼戠湢鐢熺墿锛涗笉鎶?`execute` 涓栫晫鐘舵€侀€昏緫鎸嚭涓荤嚎绋嬶紙浼氱牬鍧忓師鐗堜竴鑷存€э級銆?
> **鏇村悕璇存槑**锛氭湰椤圭洰鍘熷悕 BetterSingleCore锛屽凡鏇村悕涓?**BetterMultiCore**銆傚巻鍙?jar 浠嶅彲鍦?[Releases](https://github.com/wenrongxx/BetterMultiCore/releases) 鎸夋棫 tag 鏌ョ湅锛涘綋鍓嶅搧鐗屼粠 **1.0.0** 璧风畻銆?
## 涓嬭浇

瑙?[Releases](https://github.com/wenrongxx/BetterMultiCore/releases)銆? 
婧愮爜褰掓。瑙?[`versions/`](versions/)銆?
## 鐗堟湰鍘嗗彶锛圔etterMultiCore锛?
### 1.0.0锛堝綋鍓?路 鏂板悕棣栧彂锛?
鍔熻兘缁ф壙鍘?BetterSingleCore 1.6.x锛屽畬鎴愬搧鐗屼笌鍖呭悕杩佺Щ锛?
- 澶氭牳 preload / pathwarm锛岄珮璐熻浇 spread锛堜笉鏆傚仠锛? 
- `/particle` 澶氳浼楀苟琛屾墖鍑猴紙鍘熺増璇箟锛? 
- PlugMan 鐑嵏杞斤紙杩樺師 Brigadier particle 琛ヤ竵锛? 
- pathwarm `scan-distance`銆侀珮璐熻浇闅旇疆鎵弿銆佺┖闂插姞澶ч鐑崐寰? 
- 鍖呭悕 `com.bettermulticore` 路 鍛戒护 **`/bmc`** 路 鏉冮檺 `bettermulticore.admin` 路 绾跨▼鍓嶇紑 `BMC-`  

### 鍘嗗彶锛堝師 BetterSingleCore锛宼ag 淇濈暀锛?
| 鏃?tag | 瑕佺偣 |
|--------|------|
| 1.0.0鈥?.6.0 | 瑙佸悇 tag 鐨?release notes锛?.0.0 AI 浼戠湢鍕跨敤锛?.2.0 鏆傚仠绛栫暐宸插純鐢紝1.3+ 涓?spread |

## 鏋勫缓

鏃?Maven/Gradle锛岄渶 **JDK 21**锛?
```powershell
# 闇€瑕?leaves-api / 鏈嶅姟绔?jar 涓?brigadier 鍦ㄧ害瀹氳矾寰勶紙瑙?build.ps1锛?powershell -File BetterMultiCore\build.ps1 -Version 1.0.0
# 杈撳嚭: BetterMultiCore\dist\BetterMultiCore-1.0.0.jar
```

## 閮ㄧ讲

1. 灏?jar 鏀惧叆鏈嶅姟绔?`plugins/`  
2. 閲嶅惎锛屾垨锛堝凡瑁?PlugMan 绛夛級锛歚plugman load BetterMultiCore`  
3. 娓告垙鍐咃細`/bmc status`銆乣/bmc particle`銆乣/bmc pathwarm`  

閰嶇疆鐩綍涓?`plugins/BetterMultiCore/`锛堜笌鏃у悕鐩綍涓嶉€氱敤锛岄渶澶嶅埗鎴栨敼 `config.yml`锛夈€?
### 閰嶇疆瑕佺偣锛坄config.yml`锛?
```yaml
particle-parallel:
  enabled: true
  parallel-viewers-threshold: 4   # 瑙備紬 鈮?N 鎵嶅苟琛屽彂閫?
pathwarm:
  scan-distance: 32               # 鍙戠幇鐢熺墿鐨?AABB 鍗婂緞锛堝疄浣撳鏃跺彲 24锛?  player-distance: 48

preload:
  idle-radius-bonus: 2            # 绌洪棽鏃跺鏆栦竴鍦堝尯鍧?
adaptive:
  high-load-mspt: 25.0
  high-load-pathwarm-mobs: 16     # 楂樿礋杞芥椂姣忚疆鎵弿涓婇檺锛?0锛屼笉鏆傚仠锛?  high-load-pathwarm-interval-multiplier: 2
```

### 甯哥敤鍛戒护

| 鍛戒护 | 浣滅敤 |
|------|------|
| `/bmc status` | 鎬昏 |
| `/bmc reload` | 閲嶈浇閰嶇疆锛堝悓瀹炰緥锛屽畨鍏級 |
| `/bmc enable` \| `disable` | 寮€鍏虫湰鎻掍欢妯″潡 |
| `/bmc particle` | particle 琛ヤ竵鐘舵€?|
| `/bmc preload` \| `pathwarm` \| `monitor` | 鍚勬ā鍧楃粺璁?|

鍒悕锛歚/multicore`  

PlugMan锛?
```text
plugman unload BetterMultiCore
plugman load BetterMultiCore
plugman reload BetterMultiCore
```

## 鎬ц兘瀵圭収锛堝疄娴嬫憳瑕侊級

鍦ㄥ悓涓€鍥哄畾璐熻浇涓嬪仛浜嗗洓缁勫鐓э紙鏃犳彃浠?/ 浠呮彃浠?脳 鏃犻檮鍔犲唴瀹?/ 鏈夐檮鍔犲唴瀹癸級锛屾潯浠剁ず渚嬶細Leaves 1.21.x銆乿iew/sim distance=4銆佺害 4 鍚嶇湡浜哄鎴风銆佺害 19 瀹炰綋锛涢鐑悗 **spark profiler 30s**锛屾寚鏍囦负 spark 鐨?MSPT/TPS锛堥潪鎻掍欢鑷姤锛夈€?
### MSPT锛坢s锛?0s 绐楀彛锛?
| 鍦烘櫙 | 涓綅鏁?| p95 | **mean** | max | TPS 1m |
|------|--------|-----|----------|-----|--------|
| 鏃犳彃浠?路 鏃犻檮鍔?| 1.07 | 4.01 | **2.02** | 366 | 19.92 |
| 浠呮彃浠?路 鏃犻檮鍔?| 1.01 | 3.35 | **1.35** | 17 | 20.06 |
| 鏃犳彃浠?路 +闄勫姞 | 1.20 | 4.46 | **2.21** | 349 | 19.95 |
| 鎻掍欢 路 +闄勫姞 | 0.97 | 3.19 | **1.34** | 27 | 20.08 |

### 鎻掍欢甯︽潵鐨勫彉鍖栵紙鍚岃礋杞斤級

| 瀵规瘮 | mean | p95 | max |
|------|------|-----|-----|
| 寮€鎻掍欢锛堟棤闄勫姞锛?| **鈭?3%**锛?.02鈫?.35锛?| 鈭?6% | **鈭?5%**锛?66鈫?7锛?|
| 寮€鎻掍欢锛?闄勫姞锛?| **鈭?9%**锛?.21鈫?.34锛?| **鈭?8%** | **鈭?2%**锛?49鈫?7锛?|

### 缁撹锛堣瘹瀹烇級

1. 璇ュ浐瀹氳礋杞?+ 澶氱湡浜烘潯浠朵笅锛屽紑鎻掍欢 **mean MSPT 绾﹂檷 1/3**锛宲95 鏇寸ǔ锛屽姞鍏ュ皷宄颁粠鏁扮櫨 ms 闄嶅埌绾?20ms 閲忕骇銆? 
2. 鍥涚粍 TPS 閮借创 20鈥斺€斿樊寮傚湪 **MSPT 浣欓噺**锛屼笉鏄€屼粠 15 TPS 鏁戝洖鏉ャ€嶃€? 
3. 鐪熸鏀剁泭鍦烘櫙鏄?*鎺㈢储鍔犺浇 / 楂樺疄浣?/ 楂橀澶氳浼?particle**锛屼笉鏄┖杞姐€? 
4. 涓荤嚎绋嬭閫夋嫨鍣?`execute` 鎵撴弧鏃讹紝绾彃浠朵粛鏃犳硶鎶婁笘鐣岄€昏緫鎸蛋銆? 

## 杈圭晫锛堣闃呰锛?
- **瀹炰綋閫夋嫨鍣?/ `execute as @e` 绛変笘鐣岄€昏緫蹇呴』鐣欏湪涓荤嚎绋?*銆? 
- 鏀剁泭涓昏鍦細**鍖哄潡 IO 骞惰銆乸article 鍙戦€佹墖鍑恒€佸噺灏戞彃浠惰嚜韬富绾跨▼鍗犵敤**銆? 
- 鑻?spark 鏄剧ず澶ч噺 `@e` 鎵弿锛岄渶浠?*鍛戒护/璧勬簮鍐欐硶**浼樺寲锛坱ag銆乴imit銆佸悎骞跺叆鍙ｏ級锛屼笉鏄叧 AI銆? 

## 鍥炴粴

鍦?[Releases](https://github.com/wenrongxx/BetterMultiCore/releases) 鎴?`versions/<semver>/` 鎹㈠洖瀵瑰簲 jar 鍗冲彲銆?
## 璁稿彲

[MIT](LICENSE)
