/* 文件职责：路线缓存键：维度 + 种子 + 两个端点。 */
package net.shiroha233.roadweaver.pathlogic.structures;


record RouteKey(String dimension, long seed, long first, long second) {
}
