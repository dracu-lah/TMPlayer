import { test } from 'node:test'
import assert from 'node:assert/strict'
import { arrangeOrder, editorRows, encodeOrder, moveKey, parseOrder } from './homeOrder.ts'

const defaults = ['continue', 'recent', 'chat-1', 'chat-2', 'chat-3']

test('no stored order is the default one, a stored one is followed', () => {
    assert.deepEqual(arrangeOrder(defaults, []), defaults)
    const stored = ['chat-3', 'continue', 'chat-1', 'recent', 'chat-2']
    assert.deepEqual(arrangeOrder(defaults, stored), stored)
})

test('a key from a newer version is ignored and a new row stands at its default place', () => {
    assert.deepEqual(arrangeOrder(defaults, ['future', 'chat-3', 'chat-1', 'continue', 'recent']), ['chat-3', 'chat-1', 'chat-2', 'continue', 'recent'])
})

test('an unstarred chat drops out of the list Settings shows', () => {
    const listed = editorRows([{ chatId: 1, title: 'One' }, { chatId: 3, title: 'Three' }], ['chat-3', 'chat-2', 'continue', 'recent', 'chat-1'])
    assert.deepEqual(listed.map((e) => e.key), ['chat-3', 'continue', 'recent', 'chat-1'])
    assert.equal(listed[0].chatTitle, 'Three')
})

test('move stops at the ends and ignores a key that is not there', () => {
    assert.deepEqual(moveKey(['a', 'b', 'c'], 'b', -1), ['b', 'a', 'c'])
    assert.deepEqual(moveKey(['a', 'b', 'c'], 'a', -1), ['a', 'b', 'c'])
    assert.deepEqual(moveKey(['a', 'b', 'c'], 'c', 1), ['a', 'b', 'c'])
    assert.deepEqual(moveKey(['a', 'b', 'c'], 'z', 1), ['a', 'b', 'c'])
})

test('garbage: only keys survive, whatever the stored value is', () => {
    assert.deepEqual(parseOrder('chat-3,,Bad Key, ,recent,chat-3,\u0000,' + 'x'.repeat(100)), ['chat-3', 'recent'])
    for (const bad of [null, undefined, 5, {}, [], true, '', ',,,', '\u0000\u0001']) assert.deepEqual(parseOrder(bad), [])
    assert.equal(parseOrder(Array.from({ length: 10_000 }, (_, i) => `chat-${i}`).join(',')).length, 2000)
    assert.deepEqual(parseOrder(encodeOrder(['continue', 'chat-9'])), ['continue', 'chat-9'])
})

test('a named list on Home is a row of its own after the chats, moved like any other', () => {
    const lists = [{ id: 'abcd1234', name: 'Weekend' }]
    const listed = editorRows([{ chatId: 1, title: 'One' }], [], lists)
    assert.deepEqual(listed.map((e) => e.key), ['continue', 'recent', 'chat-1', 'list-abcd1234'])
    assert.equal(listed[3].listName, 'Weekend')
    assert.deepEqual(editorRows([{ chatId: 1, title: 'One' }], ['list-abcd1234', 'continue'], lists).map((e) => e.key), ['list-abcd1234', 'continue', 'recent', 'chat-1'])
    assert.deepEqual(parseOrder('list-abcd1234,chat-1'), ['list-abcd1234', 'chat-1'])
})
