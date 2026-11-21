package me.rerere.rikkahub.service;

interface IShizukuShell {
    String exec(String command, int timeoutMs);
}
