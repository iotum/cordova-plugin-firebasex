var helper = require("./helper");
const { setContext } = require('../lib/utilities');

module.exports = function(context) {
    setContext(context);

    // Remove the build script that was added when the plugin was installed.
    var xcodeProjectPath = helper.getXcodeProjectPath();
    helper.removeShellScriptBuildPhase(context, xcodeProjectPath);
    helper.removeGoogleTagManagerContainer(context, xcodeProjectPath);
};
