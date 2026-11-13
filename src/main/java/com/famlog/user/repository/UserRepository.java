package com.famlog.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.famlog.user.domain.User;

public interface UserRepository extends JpaRepository<User, Long> {
}
