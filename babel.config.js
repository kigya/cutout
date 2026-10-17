module.exports = function babelConfig(api) {
  api.cache.using(() => process.env.NODE_ENV);
  const presets = ['module:@react-native/babel-preset'];
  if (!api.env('test')) presets.push('nativewind/babel');
  return {
    presets,
    plugins: ['react-native-reanimated/plugin'],
  };
};
