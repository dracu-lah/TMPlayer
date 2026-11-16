import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { Message } from '@mtcute/web'
import { rawOf, toItem } from './media.ts'

// TMPlayer plays videos only (user, 2026-11-16): music, voice messages and audio sent as a file
// never reach a list. Every chat, Home and search list on the web is built from rawOf (the rules
// then read a document's name and type) or toItem.

const message = (media: Record<string, unknown> | null, id = 1) => ({
    id,
    chat: { id: 7, raw: {} },
    raw: { _: 'message' },
    media,
    text: '',
    date: new Date(1_700_000_000_000),
    groupedId: null,
}) as unknown as Message

const video = { type: 'video', isRound: false, isAnimation: false, fileName: 'Holiday.mp4', mimeType: 'video/mp4', fileSize: 1000, duration: 60, width: 1280, height: 720 }

test('music and voice messages are never a video', () => {
    for (const type of ['audio', 'voice']) {
        const msg = message({ type, fileName: 'Track 01.mp3', mimeType: 'audio/mpeg', fileSize: 1000, duration: 200 })
        assert.equal(rawOf(msg, false), null, type)
        assert.equal(toItem(msg), null, type)
    }
})

test('audio sent as a file is never listed as a video', () => {
    for (const [fileName, mimeType] of [['Lecture 3.mp3', 'audio/mpeg'], ['Voice.ogg', 'audio/ogg'], ['Talk.m4a', 'audio/mp4'], ['Album.flac', 'audio/flac'], ['untitled', 'audio/mpeg']]) {
        assert.equal(toItem(message({ type: 'document', fileName, mimeType, fileSize: 1000 })), null, fileName)
    }
})

test('the control: a video is read', () => {
    assert.equal(rawOf(message(video), false)?.kind, 'video')
})
