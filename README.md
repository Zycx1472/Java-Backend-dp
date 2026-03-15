# Java-Backend-dp
基于 Spring Boot + Redis 的高性能社交点评项目。深度应用 Redis 实现：① 秒杀系统（Lua 脚本原子操作 + Redisson 分布式锁解决方案）；② 高性能缓存（逻辑过期锁、解决缓存击穿/穿透）；③ Feed 流分发（基于 ZSet 的推模式实现）；
