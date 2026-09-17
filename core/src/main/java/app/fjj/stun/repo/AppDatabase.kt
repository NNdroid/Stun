package app.fjj.stun.repo

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Profile::class], version = 24, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao

    companion object {
        // v22 基线 schema（与 MIGRATION_21_22 的建表语句逐列一致）。
        // 迁移 22_23 不重复粘贴这份清单，改为在其后追加新列，避免两处漂移。
        private const val PROFILES_V22_COLUMN_DEFS =
            "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `sshAddr` TEXT NOT NULL, `user` TEXT NOT NULL, `pass` TEXT NOT NULL, `authType` TEXT NOT NULL, `privateKey` TEXT NOT NULL, `tunnelType` TEXT NOT NULL, `tunnelTlsEnabled` INTEGER NOT NULL, `icmpCustomPsk` TEXT NOT NULL, `icmpCustomMagic` TEXT NOT NULL, `icmpCustomPublicKey` TEXT NOT NULL, `icmpCustomMtuMode` TEXT NOT NULL, `icmpCustomMaxPayload` INTEGER NOT NULL, `icmpCustomPaceMS` INTEGER NOT NULL, `icmpCustomIdRange` TEXT NOT NULL, `proxyAddr` TEXT NOT NULL, `customHost` TEXT NOT NULL, `serverName` TEXT NOT NULL, `customPath` TEXT NOT NULL, `enableCustomPath` INTEGER NOT NULL, `proxyAuthRequired` INTEGER NOT NULL, `proxyAuthToken` TEXT NOT NULL, `proxyAuthUser` TEXT NOT NULL, `proxyAuthPass` TEXT NOT NULL, `httpPayload` TEXT NOT NULL, `type` TEXT NOT NULL, `dnsOverride` INTEGER NOT NULL, `remoteDns` TEXT NOT NULL, `localDns` TEXT NOT NULL, `udpgwVersion` TEXT NOT NULL, `udpgwAddr` TEXT NOT NULL, `geositeDirect` TEXT NOT NULL, `geoipDirect` TEXT NOT NULL, `appFilterOverride` INTEGER NOT NULL, `filterApps` TEXT NOT NULL, `filterMode` INTEGER NOT NULL, `disableStatusCheck` INTEGER NOT NULL, `verifyFingerprint` INTEGER NOT NULL, `serverFingerprint` TEXT NOT NULL, `verifyCertFingerprint` INTEGER NOT NULL, `serverCertFingerprint` TEXT NOT NULL, `alpn` TEXT NOT NULL, `keyPass` TEXT NOT NULL, `dnsTunnelDomain` TEXT NOT NULL, `dnsTunnelServers` TEXT NOT NULL, `dnsTunnelType` TEXT NOT NULL, `dnsTunnelPublicKey` TEXT NOT NULL, `dnsTunnelEDNS0` INTEGER NOT NULL, `dnsTunnelPsk` TEXT NOT NULL, `dnsTunnelMarker` TEXT NOT NULL, `kcpPassword` TEXT NOT NULL, `kcpCrypt` TEXT NOT NULL, `kcpMode` TEXT NOT NULL, `kcpDataShards` INTEGER NOT NULL, `kcpParityShards` INTEGER NOT NULL, `kcpSndWnd` INTEGER NOT NULL, `kcpRcvWnd` INTEGER NOT NULL, `kcpMtu` INTEGER NOT NULL, `kcpNoComp` INTEGER NOT NULL, `kcpSmuxVer` INTEGER NOT NULL, `kcpKeepAlive` INTEGER NOT NULL, `udpCustomPsk` TEXT NOT NULL, `udpCustomMagic` TEXT NOT NULL, `udpCustomPublicKey` TEXT NOT NULL, `udpCustomPaths` INTEGER NOT NULL, `udpCustomSockets` INTEGER NOT NULL, `udpCustomSendWindow` INTEGER NOT NULL, `udpCustomMaxPkt` INTEGER NOT NULL, `udpCustomMtuProbe` TEXT NOT NULL, `noisePublicKey` TEXT NOT NULL, `xhttpChunkSizeKB` INTEGER NOT NULL, `xhttpStreamMode` TEXT NOT NULL, `bindInterface` TEXT NOT NULL, `lastConnectedAt` INTEGER NOT NULL, `heartbeatIntervalMs` INTEGER NOT NULL, `paddingMinBytes` INTEGER NOT NULL, `masqueAlpn` TEXT NOT NULL, `totalTx` INTEGER NOT NULL, `totalRx` INTEGER NOT NULL, `sortIndex` INTEGER NOT NULL"

        private const val PROFILES_V22_COLUMN_NAMES =
            "`id`, `name`, `sshAddr`, `user`, `pass`, `authType`, `privateKey`, `tunnelType`, `tunnelTlsEnabled`, `icmpCustomPsk`, `icmpCustomMagic`, `icmpCustomPublicKey`, `icmpCustomMtuMode`, `icmpCustomMaxPayload`, `icmpCustomPaceMS`, `icmpCustomIdRange`, `proxyAddr`, `customHost`, `serverName`, `customPath`, `enableCustomPath`, `proxyAuthRequired`, `proxyAuthToken`, `proxyAuthUser`, `proxyAuthPass`, `httpPayload`, `type`, `dnsOverride`, `remoteDns`, `localDns`, `udpgwVersion`, `udpgwAddr`, `geositeDirect`, `geoipDirect`, `appFilterOverride`, `filterApps`, `filterMode`, `disableStatusCheck`, `verifyFingerprint`, `serverFingerprint`, `verifyCertFingerprint`, `serverCertFingerprint`, `alpn`, `keyPass`, `dnsTunnelDomain`, `dnsTunnelServers`, `dnsTunnelType`, `dnsTunnelPublicKey`, `dnsTunnelEDNS0`, `dnsTunnelPsk`, `dnsTunnelMarker`, `kcpPassword`, `kcpCrypt`, `kcpMode`, `kcpDataShards`, `kcpParityShards`, `kcpSndWnd`, `kcpRcvWnd`, `kcpMtu`, `kcpNoComp`, `kcpSmuxVer`, `kcpKeepAlive`, `udpCustomPsk`, `udpCustomMagic`, `udpCustomPublicKey`, `udpCustomPaths`, `udpCustomSockets`, `udpCustomSendWindow`, `udpCustomMaxPkt`, `udpCustomMtuProbe`, `noisePublicKey`, `xhttpChunkSizeKB`, `xhttpStreamMode`, `bindInterface`, `lastConnectedAt`, `heartbeatIntervalMs`, `paddingMinBytes`, `masqueAlpn`, `totalTx`, `totalRx`, `sortIndex`"

        // v23 基线 = v22 清单 + note/favorite（MIGRATION_22_23 追加的两列）。
        // v24 在其后追加 sourceSubscriptionUrl，同样不在两处重复粘贴清单。
        private const val PROFILES_V23_COLUMN_DEFS =
            "$PROFILES_V22_COLUMN_DEFS, `note` TEXT NOT NULL, `favorite` INTEGER NOT NULL"
        private const val PROFILES_V23_COLUMN_NAMES =
            "$PROFILES_V22_COLUMN_NAMES, `note`, `favorite`"

        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 订阅来源追踪：sourceSubscriptionUrl（手动节点为空串）。
                // 必须整表重建：Room 2.8.4 会把 ALTER TABLE ADD COLUMN 残留的
                // DEFAULT 值判为 "Migration didn't properly handle"（同 21_22 / 22_23）。
                db.execSQL(
                    "CREATE TABLE `profiles_new_v24` ($PROFILES_V23_COLUMN_DEFS, `sourceSubscriptionUrl` TEXT NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT INTO `profiles_new_v24` ($PROFILES_V23_COLUMN_NAMES, `sourceSubscriptionUrl`) " +
                        "SELECT $PROFILES_V23_COLUMN_NAMES, '' FROM `profiles`"
                )
                db.execSQL("DROP TABLE `profiles`")
                db.execSQL("ALTER TABLE `profiles_new_v24` RENAME TO `profiles`")
            }
        }

        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 连接详情面板改版所需的两列：note（用户备注）、favorite（星标收藏）。
                // 必须整表重建：Room 2.8.4 会把 ALTER TABLE ADD COLUMN 残留的
                // DEFAULT 值判为 "Migration didn't properly handle"（同 19_20 / 20_21 / 21_22）。
                db.execSQL(
                    "CREATE TABLE `profiles_new_v23` ($PROFILES_V22_COLUMN_DEFS, `note` TEXT NOT NULL, `favorite` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT INTO `profiles_new_v23` ($PROFILES_V22_COLUMN_NAMES, `note`, `favorite`) " +
                        "SELECT $PROFILES_V22_COLUMN_NAMES, '', 0 FROM `profiles`"
                )
                db.execSQL("DROP TABLE `profiles`")
                db.execSQL("ALTER TABLE `profiles_new_v23` RENAME TO `profiles`")
            }
        }

        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // myssh 1cb6b4a：KCP 重写为 kcptun 协议客户端。
                // kcpNoDelay 列保留弃用（Room 对额外列容忍），新字段走 kcptun 语义
                db.execSQL("ALTER TABLE profiles ADD COLUMN kcpMode TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE profiles ADD COLUMN kcpSndWnd INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiles ADD COLUMN kcpRcvWnd INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiles ADD COLUMN kcpMtu INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiles ADD COLUMN kcpNoComp INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiles ADD COLUMN kcpSmuxVer INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiles ADD COLUMN kcpKeepAlive INTEGER NOT NULL DEFAULT 0")
                // myssh 59c099e：注册名由 "kcp" 改为 "kcptun"，旧行必须同步改名，
                // 否则 GetTunnel("kcp") 查找失败且 KCP 参数段被整块跳过。
                db.execSQL("UPDATE profiles SET tunnelType = 'kcptun' WHERE tunnelType = 'kcp'")
            }
        }
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 按实体精确 schema 重建 profiles：
                // 1) 清除历史迁移 ADD COLUMN 遗留的 DEFAULT 值（Room 校验
                //    Expected(undefined) vs Found(''/'0') 不一致会判迁移失败）
                // 2) 丢弃已从实体移除的孤儿列（vaydns*、udpCustomPayload、kcpNoDelay 等）
                db.execSQL("CREATE TABLE IF NOT EXISTS `profiles_new` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `sshAddr` TEXT NOT NULL, `user` TEXT NOT NULL, `pass` TEXT NOT NULL, `authType` TEXT NOT NULL, `privateKey` TEXT NOT NULL, `tunnelType` TEXT NOT NULL, `tunnelTlsEnabled` INTEGER NOT NULL, `icmpCustomPsk` TEXT NOT NULL, `icmpCustomMagic` TEXT NOT NULL, `icmpCustomPublicKey` TEXT NOT NULL, `icmpCustomFamily` TEXT NOT NULL, `icmpCustomMtuMode` TEXT NOT NULL, `icmpCustomMaxPayload` INTEGER NOT NULL, `icmpCustomPaceMS` INTEGER NOT NULL, `icmpCustomIdRange` TEXT NOT NULL, `proxyAddr` TEXT NOT NULL, `customHost` TEXT NOT NULL, `serverName` TEXT NOT NULL, `customPath` TEXT NOT NULL, `enableCustomPath` INTEGER NOT NULL, `proxyAuthRequired` INTEGER NOT NULL, `proxyAuthToken` TEXT NOT NULL, `proxyAuthUser` TEXT NOT NULL, `proxyAuthPass` TEXT NOT NULL, `httpPayload` TEXT NOT NULL, `type` TEXT NOT NULL, `dnsOverride` INTEGER NOT NULL, `remoteDns` TEXT NOT NULL, `localDns` TEXT NOT NULL, `udpgwVersion` TEXT NOT NULL, `udpgwAddr` TEXT NOT NULL, `geositeDirect` TEXT NOT NULL, `geoipDirect` TEXT NOT NULL, `appFilterOverride` INTEGER NOT NULL, `filterApps` TEXT NOT NULL, `filterMode` INTEGER NOT NULL, `disableStatusCheck` INTEGER NOT NULL, `verifyFingerprint` INTEGER NOT NULL, `serverFingerprint` TEXT NOT NULL, `verifyCertFingerprint` INTEGER NOT NULL, `serverCertFingerprint` TEXT NOT NULL, `alpn` TEXT NOT NULL, `keyPass` TEXT NOT NULL, `dnsTunnelDomain` TEXT NOT NULL, `dnsTunnelServers` TEXT NOT NULL, `dnsTunnelType` TEXT NOT NULL, `dnsTunnelPublicKey` TEXT NOT NULL, `dnsTunnelEDNS0` INTEGER NOT NULL, `kcpPassword` TEXT NOT NULL, `kcpCrypt` TEXT NOT NULL, `kcpMode` TEXT NOT NULL, `kcpDataShards` INTEGER NOT NULL, `kcpParityShards` INTEGER NOT NULL, `kcpSndWnd` INTEGER NOT NULL, `kcpRcvWnd` INTEGER NOT NULL, `kcpMtu` INTEGER NOT NULL, `kcpNoComp` INTEGER NOT NULL, `kcpSmuxVer` INTEGER NOT NULL, `kcpKeepAlive` INTEGER NOT NULL, `udpCustomPsk` TEXT NOT NULL, `udpCustomMagic` TEXT NOT NULL, `udpCustomPublicKey` TEXT NOT NULL, `udpCustomPaths` INTEGER NOT NULL, `udpCustomSockets` INTEGER NOT NULL, `udpCustomSendWindow` INTEGER NOT NULL, `noisePublicKey` TEXT NOT NULL, `xhttpChunkSizeKB` INTEGER NOT NULL, `xhttpStreamMode` TEXT NOT NULL, `bindInterface` TEXT NOT NULL, `lastConnectedAt` INTEGER NOT NULL, `heartbeatIntervalMs` INTEGER NOT NULL, `totalTx` INTEGER NOT NULL, `totalRx` INTEGER NOT NULL, `sortIndex` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("INSERT INTO `profiles_new` (`id`, `name`, `sshAddr`, `user`, `pass`, `authType`, `privateKey`, `tunnelType`, `tunnelTlsEnabled`, `icmpCustomPsk`, `icmpCustomMagic`, `icmpCustomPublicKey`, `icmpCustomFamily`, `icmpCustomMtuMode`, `icmpCustomMaxPayload`, `icmpCustomPaceMS`, `icmpCustomIdRange`, `proxyAddr`, `customHost`, `serverName`, `customPath`, `enableCustomPath`, `proxyAuthRequired`, `proxyAuthToken`, `proxyAuthUser`, `proxyAuthPass`, `httpPayload`, `type`, `dnsOverride`, `remoteDns`, `localDns`, `udpgwVersion`, `udpgwAddr`, `geositeDirect`, `geoipDirect`, `appFilterOverride`, `filterApps`, `filterMode`, `disableStatusCheck`, `verifyFingerprint`, `serverFingerprint`, `verifyCertFingerprint`, `serverCertFingerprint`, `alpn`, `keyPass`, `dnsTunnelDomain`, `dnsTunnelServers`, `dnsTunnelType`, `dnsTunnelPublicKey`, `dnsTunnelEDNS0`, `kcpPassword`, `kcpCrypt`, `kcpMode`, `kcpDataShards`, `kcpParityShards`, `kcpSndWnd`, `kcpRcvWnd`, `kcpMtu`, `kcpNoComp`, `kcpSmuxVer`, `kcpKeepAlive`, `udpCustomPsk`, `udpCustomMagic`, `udpCustomPublicKey`, `udpCustomPaths`, `udpCustomSockets`, `udpCustomSendWindow`, `noisePublicKey`, `xhttpChunkSizeKB`, `xhttpStreamMode`, `bindInterface`, `lastConnectedAt`, `heartbeatIntervalMs`, `totalTx`, `totalRx`, `sortIndex`) SELECT `id`, `name`, `sshAddr`, `user`, `pass`, `authType`, `privateKey`, `tunnelType`, `tunnelTlsEnabled`, `icmpCustomPsk`, `icmpCustomMagic`, `icmpCustomPublicKey`, `icmpCustomFamily`, `icmpCustomMtuMode`, `icmpCustomMaxPayload`, `icmpCustomPaceMS`, `icmpCustomIdRange`, `proxyAddr`, `customHost`, `serverName`, `customPath`, `enableCustomPath`, `proxyAuthRequired`, `proxyAuthToken`, `proxyAuthUser`, `proxyAuthPass`, `httpPayload`, `type`, `dnsOverride`, `remoteDns`, `localDns`, `udpgwVersion`, `udpgwAddr`, `geositeDirect`, `geoipDirect`, `appFilterOverride`, `filterApps`, `filterMode`, `disableStatusCheck`, `verifyFingerprint`, `serverFingerprint`, `verifyCertFingerprint`, `serverCertFingerprint`, `alpn`, `keyPass`, `dnsTunnelDomain`, `dnsTunnelServers`, `dnsTunnelType`, `dnsTunnelPublicKey`, `dnsTunnelEDNS0`, `kcpPassword`, `kcpCrypt`, `kcpMode`, `kcpDataShards`, `kcpParityShards`, `kcpSndWnd`, `kcpRcvWnd`, `kcpMtu`, `kcpNoComp`, `kcpSmuxVer`, `kcpKeepAlive`, `udpCustomPsk`, `udpCustomMagic`, `udpCustomPublicKey`, `udpCustomPaths`, `udpCustomSockets`, `udpCustomSendWindow`, `noisePublicKey`, `xhttpChunkSizeKB`, `xhttpStreamMode`, `bindInterface`, `lastConnectedAt`, `heartbeatIntervalMs`, `totalTx`, `totalRx`, `sortIndex` FROM `profiles`")
                db.execSQL("DROP TABLE `profiles`")
                db.execSQL("ALTER TABLE `profiles_new` RENAME TO `profiles`")
                // 实体变更后 identity hash 也变了：删掉 room_master_table，
                // Room 下次打开时重新计算并写入正确的 hash，不再误判迁移失败。
                db.execSQL("DROP TABLE IF EXISTS room_master_table")
            }
        }
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // myssh ce76928：dns_custom 新增 PSK 鉴权与自定义 marker。
                // 必须走重建：Room 2.8.4 会对 ALTER TABLE ADD COLUMN ... DEFAULT 的
                // 残留默认值报 "Migration didn't properly handle"（同 19_20 的教训）。
                db.execSQL("CREATE TABLE `profiles_new_v21` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `sshAddr` TEXT NOT NULL, `user` TEXT NOT NULL, `pass` TEXT NOT NULL, `authType` TEXT NOT NULL, `privateKey` TEXT NOT NULL, `tunnelType` TEXT NOT NULL, `tunnelTlsEnabled` INTEGER NOT NULL, `icmpCustomPsk` TEXT NOT NULL, `icmpCustomMagic` TEXT NOT NULL, `icmpCustomPublicKey` TEXT NOT NULL, `icmpCustomFamily` TEXT NOT NULL, `icmpCustomMtuMode` TEXT NOT NULL, `icmpCustomMaxPayload` INTEGER NOT NULL, `icmpCustomPaceMS` INTEGER NOT NULL, `icmpCustomIdRange` TEXT NOT NULL, `proxyAddr` TEXT NOT NULL, `customHost` TEXT NOT NULL, `serverName` TEXT NOT NULL, `customPath` TEXT NOT NULL, `enableCustomPath` INTEGER NOT NULL, `proxyAuthRequired` INTEGER NOT NULL, `proxyAuthToken` TEXT NOT NULL, `proxyAuthUser` TEXT NOT NULL, `proxyAuthPass` TEXT NOT NULL, `httpPayload` TEXT NOT NULL, `type` TEXT NOT NULL, `dnsOverride` INTEGER NOT NULL, `remoteDns` TEXT NOT NULL, `localDns` TEXT NOT NULL, `udpgwVersion` TEXT NOT NULL, `udpgwAddr` TEXT NOT NULL, `geositeDirect` TEXT NOT NULL, `geoipDirect` TEXT NOT NULL, `appFilterOverride` INTEGER NOT NULL, `filterApps` TEXT NOT NULL, `filterMode` INTEGER NOT NULL, `disableStatusCheck` INTEGER NOT NULL, `verifyFingerprint` INTEGER NOT NULL, `serverFingerprint` TEXT NOT NULL, `verifyCertFingerprint` INTEGER NOT NULL, `serverCertFingerprint` TEXT NOT NULL, `alpn` TEXT NOT NULL, `keyPass` TEXT NOT NULL, `dnsTunnelDomain` TEXT NOT NULL, `dnsTunnelServers` TEXT NOT NULL, `dnsTunnelType` TEXT NOT NULL, `dnsTunnelPublicKey` TEXT NOT NULL, `dnsTunnelEDNS0` INTEGER NOT NULL, `dnsTunnelPsk` TEXT NOT NULL, `dnsTunnelMarker` TEXT NOT NULL, `kcpPassword` TEXT NOT NULL, `kcpCrypt` TEXT NOT NULL, `kcpMode` TEXT NOT NULL, `kcpDataShards` INTEGER NOT NULL, `kcpParityShards` INTEGER NOT NULL, `kcpSndWnd` INTEGER NOT NULL, `kcpRcvWnd` INTEGER NOT NULL, `kcpMtu` INTEGER NOT NULL, `kcpNoComp` INTEGER NOT NULL, `kcpSmuxVer` INTEGER NOT NULL, `kcpKeepAlive` INTEGER NOT NULL, `udpCustomPsk` TEXT NOT NULL, `udpCustomMagic` TEXT NOT NULL, `udpCustomPublicKey` TEXT NOT NULL, `udpCustomPaths` INTEGER NOT NULL, `udpCustomSockets` INTEGER NOT NULL, `udpCustomSendWindow` INTEGER NOT NULL, `noisePublicKey` TEXT NOT NULL, `xhttpChunkSizeKB` INTEGER NOT NULL, `xhttpStreamMode` TEXT NOT NULL, `bindInterface` TEXT NOT NULL, `lastConnectedAt` INTEGER NOT NULL, `heartbeatIntervalMs` INTEGER NOT NULL, `totalTx` INTEGER NOT NULL, `totalRx` INTEGER NOT NULL, `sortIndex` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("INSERT INTO `profiles_new_v21` (`id`, `name`, `sshAddr`, `user`, `pass`, `authType`, `privateKey`, `tunnelType`, `tunnelTlsEnabled`, `icmpCustomPsk`, `icmpCustomMagic`, `icmpCustomPublicKey`, `icmpCustomFamily`, `icmpCustomMtuMode`, `icmpCustomMaxPayload`, `icmpCustomPaceMS`, `icmpCustomIdRange`, `proxyAddr`, `customHost`, `serverName`, `customPath`, `enableCustomPath`, `proxyAuthRequired`, `proxyAuthToken`, `proxyAuthUser`, `proxyAuthPass`, `httpPayload`, `type`, `dnsOverride`, `remoteDns`, `localDns`, `udpgwVersion`, `udpgwAddr`, `geositeDirect`, `geoipDirect`, `appFilterOverride`, `filterApps`, `filterMode`, `disableStatusCheck`, `verifyFingerprint`, `serverFingerprint`, `verifyCertFingerprint`, `serverCertFingerprint`, `alpn`, `keyPass`, `dnsTunnelDomain`, `dnsTunnelServers`, `dnsTunnelType`, `dnsTunnelPublicKey`, `dnsTunnelEDNS0`, `dnsTunnelPsk`, `dnsTunnelMarker`, `kcpPassword`, `kcpCrypt`, `kcpMode`, `kcpDataShards`, `kcpParityShards`, `kcpSndWnd`, `kcpRcvWnd`, `kcpMtu`, `kcpNoComp`, `kcpSmuxVer`, `kcpKeepAlive`, `udpCustomPsk`, `udpCustomMagic`, `udpCustomPublicKey`, `udpCustomPaths`, `udpCustomSockets`, `udpCustomSendWindow`, `noisePublicKey`, `xhttpChunkSizeKB`, `xhttpStreamMode`, `bindInterface`, `lastConnectedAt`, `heartbeatIntervalMs`, `totalTx`, `totalRx`, `sortIndex`) SELECT `id`, `name`, `sshAddr`, `user`, `pass`, `authType`, `privateKey`, `tunnelType`, `tunnelTlsEnabled`, `icmpCustomPsk`, `icmpCustomMagic`, `icmpCustomPublicKey`, `icmpCustomFamily`, `icmpCustomMtuMode`, `icmpCustomMaxPayload`, `icmpCustomPaceMS`, `icmpCustomIdRange`, `proxyAddr`, `customHost`, `serverName`, `customPath`, `enableCustomPath`, `proxyAuthRequired`, `proxyAuthToken`, `proxyAuthUser`, `proxyAuthPass`, `httpPayload`, `type`, `dnsOverride`, `remoteDns`, `localDns`, `udpgwVersion`, `udpgwAddr`, `geositeDirect`, `geoipDirect`, `appFilterOverride`, `filterApps`, `filterMode`, `disableStatusCheck`, `verifyFingerprint`, `serverFingerprint`, `verifyCertFingerprint`, `serverCertFingerprint`, `alpn`, `keyPass`, `dnsTunnelDomain`, `dnsTunnelServers`, `dnsTunnelType`, `dnsTunnelPublicKey`, `dnsTunnelEDNS0`, '' AS x1, '' AS x2, `kcpPassword`, `kcpCrypt`, `kcpMode`, `kcpDataShards`, `kcpParityShards`, `kcpSndWnd`, `kcpRcvWnd`, `kcpMtu`, `kcpNoComp`, `kcpSmuxVer`, `kcpKeepAlive`, `udpCustomPsk`, `udpCustomMagic`, `udpCustomPublicKey`, `udpCustomPaths`, `udpCustomSockets`, `udpCustomSendWindow`, `noisePublicKey`, `xhttpChunkSizeKB`, `xhttpStreamMode`, `bindInterface`, `lastConnectedAt`, `heartbeatIntervalMs`, `totalTx`, `totalRx`, `sortIndex` FROM `profiles`")
                db.execSQL("DROP TABLE `profiles`")
                db.execSQL("ALTER TABLE `profiles_new_v21` RENAME TO `profiles`")
            }
        }

        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // myssh 152c556: icmp_custom_family 从 ProxyConfig 删除（handler 不再读取），
                // 新增 UDP Custom MTU 探测（udpCustomMaxPkt/MtuProbe）和
                // h2tunnel 填充调优（paddingMinBytes）、masque ALPN 选择（masqueAlpn）。
                // 必须重建：Room 2.8.4 对孤儿列报 "Migration didn't properly handle"。
                db.execSQL("CREATE TABLE `profiles_new_v22` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `sshAddr` TEXT NOT NULL, `user` TEXT NOT NULL, `pass` TEXT NOT NULL, `authType` TEXT NOT NULL, `privateKey` TEXT NOT NULL, `tunnelType` TEXT NOT NULL, `tunnelTlsEnabled` INTEGER NOT NULL, `icmpCustomPsk` TEXT NOT NULL, `icmpCustomMagic` TEXT NOT NULL, `icmpCustomPublicKey` TEXT NOT NULL, `icmpCustomMtuMode` TEXT NOT NULL, `icmpCustomMaxPayload` INTEGER NOT NULL, `icmpCustomPaceMS` INTEGER NOT NULL, `icmpCustomIdRange` TEXT NOT NULL, `proxyAddr` TEXT NOT NULL, `customHost` TEXT NOT NULL, `serverName` TEXT NOT NULL, `customPath` TEXT NOT NULL, `enableCustomPath` INTEGER NOT NULL, `proxyAuthRequired` INTEGER NOT NULL, `proxyAuthToken` TEXT NOT NULL, `proxyAuthUser` TEXT NOT NULL, `proxyAuthPass` TEXT NOT NULL, `httpPayload` TEXT NOT NULL, `type` TEXT NOT NULL, `dnsOverride` INTEGER NOT NULL, `remoteDns` TEXT NOT NULL, `localDns` TEXT NOT NULL, `udpgwVersion` TEXT NOT NULL, `udpgwAddr` TEXT NOT NULL, `geositeDirect` TEXT NOT NULL, `geoipDirect` TEXT NOT NULL, `appFilterOverride` INTEGER NOT NULL, `filterApps` TEXT NOT NULL, `filterMode` INTEGER NOT NULL, `disableStatusCheck` INTEGER NOT NULL, `verifyFingerprint` INTEGER NOT NULL, `serverFingerprint` TEXT NOT NULL, `verifyCertFingerprint` INTEGER NOT NULL, `serverCertFingerprint` TEXT NOT NULL, `alpn` TEXT NOT NULL, `keyPass` TEXT NOT NULL, `dnsTunnelDomain` TEXT NOT NULL, `dnsTunnelServers` TEXT NOT NULL, `dnsTunnelType` TEXT NOT NULL, `dnsTunnelPublicKey` TEXT NOT NULL, `dnsTunnelEDNS0` INTEGER NOT NULL, `dnsTunnelPsk` TEXT NOT NULL, `dnsTunnelMarker` TEXT NOT NULL, `kcpPassword` TEXT NOT NULL, `kcpCrypt` TEXT NOT NULL, `kcpMode` TEXT NOT NULL, `kcpDataShards` INTEGER NOT NULL, `kcpParityShards` INTEGER NOT NULL, `kcpSndWnd` INTEGER NOT NULL, `kcpRcvWnd` INTEGER NOT NULL, `kcpMtu` INTEGER NOT NULL, `kcpNoComp` INTEGER NOT NULL, `kcpSmuxVer` INTEGER NOT NULL, `kcpKeepAlive` INTEGER NOT NULL, `udpCustomPsk` TEXT NOT NULL, `udpCustomMagic` TEXT NOT NULL, `udpCustomPublicKey` TEXT NOT NULL, `udpCustomPaths` INTEGER NOT NULL, `udpCustomSockets` INTEGER NOT NULL, `udpCustomSendWindow` INTEGER NOT NULL, `udpCustomMaxPkt` INTEGER NOT NULL, `udpCustomMtuProbe` TEXT NOT NULL, `noisePublicKey` TEXT NOT NULL, `xhttpChunkSizeKB` INTEGER NOT NULL, `xhttpStreamMode` TEXT NOT NULL, `bindInterface` TEXT NOT NULL, `lastConnectedAt` INTEGER NOT NULL, `heartbeatIntervalMs` INTEGER NOT NULL, `paddingMinBytes` INTEGER NOT NULL, `masqueAlpn` TEXT NOT NULL, `totalTx` INTEGER NOT NULL, `totalRx` INTEGER NOT NULL, `sortIndex` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("INSERT INTO `profiles_new_v22` (`id`, `name`, `sshAddr`, `user`, `pass`, `authType`, `privateKey`, `tunnelType`, `tunnelTlsEnabled`, `icmpCustomPsk`, `icmpCustomMagic`, `icmpCustomPublicKey`, `icmpCustomMtuMode`, `icmpCustomMaxPayload`, `icmpCustomPaceMS`, `icmpCustomIdRange`, `proxyAddr`, `customHost`, `serverName`, `customPath`, `enableCustomPath`, `proxyAuthRequired`, `proxyAuthToken`, `proxyAuthUser`, `proxyAuthPass`, `httpPayload`, `type`, `dnsOverride`, `remoteDns`, `localDns`, `udpgwVersion`, `udpgwAddr`, `geositeDirect`, `geoipDirect`, `appFilterOverride`, `filterApps`, `filterMode`, `disableStatusCheck`, `verifyFingerprint`, `serverFingerprint`, `verifyCertFingerprint`, `serverCertFingerprint`, `alpn`, `keyPass`, `dnsTunnelDomain`, `dnsTunnelServers`, `dnsTunnelType`, `dnsTunnelPublicKey`, `dnsTunnelEDNS0`, `dnsTunnelPsk`, `dnsTunnelMarker`, `kcpPassword`, `kcpCrypt`, `kcpMode`, `kcpDataShards`, `kcpParityShards`, `kcpSndWnd`, `kcpRcvWnd`, `kcpMtu`, `kcpNoComp`, `kcpSmuxVer`, `kcpKeepAlive`, `udpCustomPsk`, `udpCustomMagic`, `udpCustomPublicKey`, `udpCustomPaths`, `udpCustomSockets`, `udpCustomSendWindow`, `udpCustomMaxPkt`, `udpCustomMtuProbe`, `noisePublicKey`, `xhttpChunkSizeKB`, `xhttpStreamMode`, `bindInterface`, `lastConnectedAt`, `heartbeatIntervalMs`, `paddingMinBytes`, `masqueAlpn`, `totalTx`, `totalRx`, `sortIndex`) SELECT `id`, `name`, `sshAddr`, `user`, `pass`, `authType`, `privateKey`, `tunnelType`, `tunnelTlsEnabled`, `icmpCustomPsk`, `icmpCustomMagic`, `icmpCustomPublicKey`, `icmpCustomMtuMode`, `icmpCustomMaxPayload`, `icmpCustomPaceMS`, `icmpCustomIdRange`, `proxyAddr`, `customHost`, `serverName`, `customPath`, `enableCustomPath`, `proxyAuthRequired`, `proxyAuthToken`, `proxyAuthUser`, `proxyAuthPass`, `httpPayload`, `type`, `dnsOverride`, `remoteDns`, `localDns`, `udpgwVersion`, `udpgwAddr`, `geositeDirect`, `geoipDirect`, `appFilterOverride`, `filterApps`, `filterMode`, `disableStatusCheck`, `verifyFingerprint`, `serverFingerprint`, `verifyCertFingerprint`, `serverCertFingerprint`, `alpn`, `keyPass`, `dnsTunnelDomain`, `dnsTunnelServers`, `dnsTunnelType`, `dnsTunnelPublicKey`, `dnsTunnelEDNS0`, `dnsTunnelPsk`, `dnsTunnelMarker`, `kcpPassword`, `kcpCrypt`, `kcpMode`, `kcpDataShards`, `kcpParityShards`, `kcpSndWnd`, `kcpRcvWnd`, `kcpMtu`, `kcpNoComp`, `kcpSmuxVer`, `kcpKeepAlive`, `udpCustomPsk`, `udpCustomMagic`, `udpCustomPublicKey`, `udpCustomPaths`, `udpCustomSockets`, `udpCustomSendWindow`, 0, '', `noisePublicKey`, `xhttpChunkSizeKB`, `xhttpStreamMode`, `bindInterface`, `lastConnectedAt`, `heartbeatIntervalMs`, 0, '', `totalTx`, `totalRx`, `sortIndex` FROM `profiles`")
                db.execSQL("DROP TABLE `profiles`")
                db.execSQL("ALTER TABLE `profiles_new_v22` RENAME TO `profiles`")
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // myssh 0e1f602：新增 icmp_custom 隧道（SSH-over-ICMP）
                db.execSQL("ALTER TABLE profiles ADD COLUMN icmpCustomPsk TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE profiles ADD COLUMN icmpCustomMagic TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE profiles ADD COLUMN icmpCustomPublicKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE profiles ADD COLUMN icmpCustomFamily TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE profiles ADD COLUMN icmpCustomMtuMode TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE profiles ADD COLUMN icmpCustomMaxPayload INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiles ADD COLUMN icmpCustomPaceMS INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiles ADD COLUMN icmpCustomIdRange TEXT NOT NULL DEFAULT ''")
            }
        }
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // myssh 4735512 类型收敛：TLS 三态 → tunnelTlsEnabled 布尔。
                // 顺序关键：固定 TLS 类型先置 1，再映射类型名（明文变体置 0），
                // 否则 h2c 行会被 'h2' 的固定 TLS 置 1 误伤。
                db.execSQL("ALTER TABLE profiles ADD COLUMN tunnelTlsEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE profiles SET tunnelTlsEnabled = 1 WHERE tunnelType IN ('tls','wss','h2','grpc','xhttp')")
                db.execSQL("UPDATE profiles SET tunnelType = 'raw',        tunnelTlsEnabled = 0 WHERE tunnelType = 'base'")
                db.execSQL("UPDATE profiles SET tunnelType = 'websocket',  tunnelTlsEnabled = 0 WHERE tunnelType = 'ws'")
                db.execSQL("UPDATE profiles SET tunnelType = 'h2',         tunnelTlsEnabled = 0 WHERE tunnelType = 'h2c'")
                db.execSQL("UPDATE profiles SET tunnelType = 'grpc',       tunnelTlsEnabled = 0 WHERE tunnelType = 'grpcc'")
                db.execSQL("UPDATE profiles SET tunnelType = 'xhttp',      tunnelTlsEnabled = 0 WHERE tunnelType = 'xhttpc'")
                db.execSQL("UPDATE profiles SET tunnelType = 'webtransport' WHERE tunnelType = 'wt'")
            }
        }
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN lastConnectedAt INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN xhttpStreamMode TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE profiles ADD COLUMN bindInterface TEXT NOT NULL DEFAULT ''")
            }
        }
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN dnsTunnelPublicKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE profiles ADD COLUMN udpCustomPublicKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE profiles ADD COLUMN udpCustomPaths INTEGER NOT NULL DEFAULT 0")
                // Existing profiles used one shared key. Copy it into both protocol-specific
                // columns so an upgrade cannot silently disable Noise authentication.
                db.execSQL("UPDATE profiles SET dnsTunnelPublicKey = noisePublicKey WHERE noisePublicKey != ''")
                db.execSQL("UPDATE profiles SET udpCustomPublicKey = noisePublicKey WHERE noisePublicKey != ''")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v1 -> v2 migration
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v2 -> v3 migration
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v3 -> v4 migration
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v4 -> v5 migration
            }
        }
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN dnsTunnelEDNS0 INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiles ADD COLUMN udpCustomSockets INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiles ADD COLUMN udpCustomSendWindow INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiles ADD COLUMN xhttpChunkSizeKB INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiles ADD COLUMN heartbeatIntervalMs INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Rename tunnelType 'dns' to 'dns_custom'
                db.execSQL("UPDATE profiles SET tunnelType = 'dns_custom' WHERE tunnelType = 'dns'")
            }
        }
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN noisePublicKey TEXT NOT NULL DEFAULT ''")
            }
        }
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN udpCustomPsk TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE profiles ADD COLUMN udpCustomMagic TEXT NOT NULL DEFAULT 'UDPC'")
            }
        }
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN kcpPassword TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE profiles ADD COLUMN kcpCrypt TEXT NOT NULL DEFAULT 'aes'")
                db.execSQL("ALTER TABLE profiles ADD COLUMN kcpNoDelay INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE profiles ADD COLUMN kcpDataShards INTEGER NOT NULL DEFAULT 10")
                db.execSQL("ALTER TABLE profiles ADD COLUMN kcpParityShards INTEGER NOT NULL DEFAULT 3")
                db.execSQL("ALTER TABLE profiles ADD COLUMN udpCustomPayload TEXT NOT NULL DEFAULT ''")
            }
        }
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN dnsTunnelDomain TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE profiles ADD COLUMN dnsTunnelServers TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE profiles ADD COLUMN dnsTunnelType TEXT NOT NULL DEFAULT 'txt'")
            }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN sortIndex INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN vaydnsPubkey TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE profiles ADD COLUMN vaydnsDomain TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE profiles ADD COLUMN vaydnsMode TEXT NOT NULL DEFAULT 'doh'")
                db.execSQL("ALTER TABLE profiles ADD COLUMN vaydnsResolvers TEXT NOT NULL DEFAULT 'https://dns.google/dns-query,https://cloudflare-dns.com/dns-query'")
                db.execSQL("ALTER TABLE profiles ADD COLUMN vaydnsQueryType TEXT NOT NULL DEFAULT 'txt'")
            }
        }
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "stun_database"
                )
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                    MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                    MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
                    MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22,
                    MIGRATION_22_23, MIGRATION_23_24
                )
                .fallbackToDestructiveMigration(true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
