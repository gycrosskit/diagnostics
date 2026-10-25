Pod::Spec.new do |spec|
  spec.name = 'GYDiagnosticsSdk'
  spec.summary = '无宿主依赖的诊断 SDK 配置与生命周期接口'
  spec.version = '1.0.1'
  spec.homepage = 'https://github.com/gycrosskit/diagnostics'
  spec.license = { :type => 'Apache-2.0', :file => 'LICENSE' }
  spec.author = { 'GY CrossKit' => 'https://github.com/gycrosskit' }
  spec.source = { :git => 'https://github.com/gycrosskit/diagnostics.git', :tag => spec.version.to_s }
  spec.ios.deployment_target = '15.0'
  spec.swift_version = '5.9'
  spec.frameworks = 'Foundation'
  spec.source_files = 'ios-sdk/Core/*.swift'
end
