package com.example.data

import kotlinx.coroutines.flow.Flow

class UserRepository(private val userDao: UserDao) {
    val activeUser: Flow<UserEntity?> = userDao.getActiveUserFlow()

    suspend fun getCurrentUser(): UserEntity? = userDao.getActiveUser()

    suspend fun saveUser(user: UserEntity) = userDao.insertUser(user)

    suspend fun clearUser() = userDao.clearUser()
}
