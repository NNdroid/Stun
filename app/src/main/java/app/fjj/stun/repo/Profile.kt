package app.fjj.stun.repo

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "profiles")
data class Profile(
    @PrimaryKey
    var id: String = UUID.randomUUID().toString(),
    var name: String = "Server config",
    var sshAddr: String = "198.98.61.214:666",
    var user: String = "opentunnel.net-test007",
    var pass: String = "521qqwq",
    var tunnelType: String = "tls",
    var proxyAddr: String = "198.98.61.214:443",
    var customHost: String = "microsoft.com",
    var type: String = "ssh"
)
