import express from "express";

export async function load() {
    const chalk = await import("chalk");
    return [express, chalk];
}
