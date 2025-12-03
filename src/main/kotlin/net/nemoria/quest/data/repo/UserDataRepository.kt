package net.nemoria.quest.data.repo

import net.nemoria.quest.data.user.UserData
import java.util.UUID

interface UserDataRepository {
    fun load(uuid: UUID): UserData
    fun save(data: UserData)
}
