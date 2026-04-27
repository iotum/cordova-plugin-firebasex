var exec = require('cordova/exec');

/**
 * Compatibility layer for apps that previously used cordova-plugin-iotum-badge.
 * Routes calls through FirebasePlugin badge APIs.
 */
exports.set = function (count, callback, errorCallback) {
    var normalized = Math.max(0, parseInt(count, 10) || 0);
    exec(callback || null, errorCallback || null, 'FirebasePlugin', 'setBadgeNumber', [normalized]);
};

exports.clear = function (callback, errorCallback) {
    exec(callback || null, errorCallback || null, 'FirebasePlugin', 'setBadgeNumber', [0]);
};

exports.get = function (callback, errorCallback) {
    exec(callback || null, errorCallback || null, 'FirebasePlugin', 'getBadgeNumber', []);
};
