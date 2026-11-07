config.module.rules.push({
  test: /skiko\.mjs$/,
  use: [{loader: require('path').resolve(__dirname, '../../../../demo-web/scripts/skiko-offline-loader.cjs')}]
});
