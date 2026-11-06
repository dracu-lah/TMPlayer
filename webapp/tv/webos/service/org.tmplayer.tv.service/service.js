'use strict'
// The Luna side of the loopback bridge (bridge.js): `start` brings the bridge up on 127.0.0.1 and
// answers { port, token } to the app's local index.html, which hands them to the hosted page. webOS
// stops a JS service after 5 s without an activity, so a keepAlive activity is held while the page
// is connected and completed 60 s after it goes (the bridge has no timers when nothing plays).
// ES5, no dependencies besides webos-service, which the TV provides.

var bridgeModule = require('./bridge')

var ID = 'org.tmplayer.tv.service'
var HOLD_MS = 60000

function build(Service, options) {
    var opt = options || {}
    var service = new Service(ID)
    var bridge = null
    var activity = null
    var wanted = false

    var lease = bridgeModule.createLease(
        function () {
            wanted = true
            service.activityManager.create('keepAlive', function (a) {
                if (wanted) activity = a
                else service.activityManager.complete(a, function () {})
            })
        },
        function () {
            wanted = false
            if (activity) service.activityManager.complete(activity, function () {})
            activity = null
        },
        typeof opt.hold === 'number' ? opt.hold : HOLD_MS
    )

    function ensure(origin, done) {
        if (bridge) {
            bridge.setOrigin(origin)
            return done(null)
        }
        var b = bridgeModule.createBridge({
            origin: origin,
            grace: opt.grace,
            log: function (what, why) {
                console.log('[tmplayer-service]', what, why)
            },
            onPage: function (connected) {
                if (connected) lease.up()
                else lease.down()
            },
        })
        b.start(function (err) {
            if (err) return done(err)
            bridge = b
            done(null)
        })
    }

    service.register('start', function (message) {
        var origin = message.payload && message.payload.origin
        if (typeof origin !== 'string' || !/^https?:\/\/[^\/?#\s]+$/.test(origin)) {
            return message.respond({ returnValue: false, errorText: 'origin' })
        }
        ensure(origin, function (err) {
            if (err) return message.respond({ returnValue: false, errorText: String((err && err.code) || err) })
            // Alive until the page has had HOLD_MS to connect; its first poll takes over.
            lease.up()
            lease.down()
            message.respond({ returnValue: true, port: bridge.port(), token: bridge.token() })
        })
    })

    return { service: service, lease: lease, bridge: function () { return bridge } }
}

var Service = null
try {
    Service = require('webos-service')
} catch (e) {
    Service = null
}
if (Service) {
    // A failure in one request must not take the service down.
    process.on('uncaughtException', function (e) {
        console.log('[tmplayer-service] uncaught', e && e.stack ? e.stack : e)
    })
    build(Service)
}

module.exports = { build: build, ID: ID }
