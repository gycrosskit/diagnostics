#!/usr/bin/env ruby
# Preload via the installed pod launcher; never preload this verifier in children.
ENV.delete('RUBYOPT')
require 'cocoapods'
require 'digest'
require 'fileutils'
require 'json'
require 'open3'
require 'tmpdir'
require 'rbconfig'

# Validator may skip Xcode silently; every release consumer must compile for real.
class TaggedPodValidator < Pod::Validator
  attr_reader :compiler_proof
  attr_accessor :compiler_log

  def xcodebuild(action, scheme, configuration, deployment_target:)
    output = super
    if action == 'build'
      File.write(compiler_log, output)
      compile_rows = output.lines.select { |line| line.match?(/SwiftCompile|CompileSwift|CompileC /) }
      swift_files = Array(spec.attributes_hash['source_files']).flat_map { |pattern| Dir.glob(File.join(file.dirname, pattern)) }.select { |path| path.end_with?('.swift') }.map { |path| File.basename(path) }.uniq
      raise 'No successful compiler execution' unless !swift_files.empty? && output.include?('** BUILD SUCCEEDED **') && swift_files.all? { |source| compile_rows.any? { |row| row.include?(source) } }
      @compiler_proof = { action: action, scheme: scheme, configuration: configuration,
                          compileRows: compile_rows.length, ownSwiftFilesCompiled: swift_files, outputSha256: Digest::SHA256.hexdigest(output) }
    end
    output
  end
end

check_only = ARGV == ['--check-only']
abort 'Unknown arguments' unless ARGV.empty? || check_only
version = ENV['VERSION'] || (check_only && Pod::Specification.from_file('GYDiagnosticsNative.podspec').version.to_s)
abort 'An immutable numeric release tag is required' unless version && version.match?(/\A[0-9][0-9A-Za-z._-]*\z/)
root = Dir.pwd
repository = 'https://github.com/gycrosskit/diagnostics.git'
output = File.join(root, 'ci-diagnostics', 'tagged-ios')
FileUtils.mkdir_p(output)
proof = { version: version, rubyVersion: RUBY_VERSION, rubyExecutable: RbConfig.ruby, cocoaPodsVersion: Pod::VERSION, metadataInput: 'local exact-tag podspec only; compiled sources downloaded by CocoaPods', pods: [] }
puts "Tagged iOS runtime: Ruby #{RUBY_VERSION}, CocoaPods #{Pod::VERSION}, #{RbConfig.ruby}"
unless check_only
  refs, error, status = Open3.capture3('git', 'ls-remote', '--tags', repository, "refs/tags/#{version}", "refs/tags/#{version}^{}")
  abort "Cannot resolve tag: #{error}" unless status.success?
  entries = refs.lines.map(&:split)
  tag_sha = (entries.find { |row| row[1] == "refs/tags/#{version}^{}" } || entries.find { |row| row[1] == "refs/tags/#{version}" })&.first
  abort 'Missing exact tag SHA' unless tag_sha&.match?(/\A[a-f0-9]{40}\z/)
  head, error, status = Open3.capture3('git', 'rev-parse', 'HEAD')
  abort 'Release metadata checkout must equal the exact tag' unless status.success? && head.strip == tag_sha
  proof[:tagCommit] = tag_sha
end
%w[GYDiagnosticsNative GYDiagnosticsSdk GYDiagnosticsBugly GYDiagnosticsUmeng].each do |name|
  file = File.join(root, "#{name}.podspec")
  spec = Pod::Specification.from_file(file)
  abort "#{name}: version/source mismatch" unless spec.version.to_s == version && spec.source[:git] == repository && spec.source[:tag] == version
  unless check_only
    tagged_metadata, error, status = Open3.capture3('git', 'show', "#{tag_sha}:#{name}.podspec")
    abort "#{name}: metadata bytes differ from tag: #{error}" unless status.success? && tagged_metadata.b == File.binread(file)
  end
  next if check_only
  validator = TaggedPodValidator.new(file, ['https://cdn.cocoapods.org/'], ['ios'])
  validator.compiler_log = File.join(output, "#{name}-compiler.log")
  validator.local = false
  validator.use_frameworks = true
  validator.use_static_frameworks = true
  validator.allow_warnings = true
  validator.fail_fast = true
  validator.no_clean = true
  validator.validation_dir = File.join(ENV.fetch('RUNNER_TEMP'), 'diagnostics-tagged-ios', name)
  expected_pods = [name]
  if %w[GYDiagnosticsBugly GYDiagnosticsUmeng].include?(name)
    # :podspec supplies metadata; :path would compile local checkout sources.
    validator.external_podspecs = File.join(root, 'GYDiagnosticsSdk.podspec')
    expected_pods << 'GYDiagnosticsSdk'
  end
  abort "#{name}: #{validator.failure_reason}" unless validator.validate
  abort "#{name}: compiler was skipped" unless validator.compiler_proof
  lock_path = validator.validation_dir + 'Podfile.lock'
  abort "#{name}: missing consumer lock" unless lock_path.file?
  lock = Pod::Lockfile.from_file(lock_path)
  external = lock.internal_data.fetch('EXTERNAL SOURCES')
  checkout = lock.internal_data.fetch('CHECKOUT OPTIONS')
  abort "#{name}: unexpected external source" unless external.keys.sort == expected_pods.sort
  abort "#{name}: local source path" if external.values.any? { |value| value.key?(':path') }
  source_files = {}
  expected_pods.each do |pod|
    metadata = external.fetch(pod)
    options = checkout.fetch(pod)
    abort "#{pod}: metadata source mismatch" unless metadata == { ':podspec' => File.join(root, "#{pod}.podspec") }
    abort "#{pod}: downloaded commit/version mismatch" unless options[':git'] == repository && options[':commit'] == tag_sha && !options.key?(':path') && lock.version(pod).to_s == version
    installed_spec = Pod::Specification.from_file(validator.validation_dir + "Pods/Local Podspecs/#{pod}.podspec.json")
    abort "#{pod}: installed spec mismatch" unless installed_spec.version.to_s == version && installed_spec.source[:git] == repository && installed_spec.source[:tag] == version
    files = Array(installed_spec.attributes_hash['source_files']).flat_map { |pattern| Dir.glob(File.join(root, pattern)) }.select { |path| File.file?(path) }.uniq.sort
    abort "#{pod}: no source files" if files.empty?
    source_files[pod] = files.to_h do |source|
      relative = Pathname(source).relative_path_from(Pathname(root)).to_s
      downloaded = validator.validation_dir + "Pods/#{pod}/#{relative}"
      digest = Digest::SHA256.file(source).hexdigest
      abort "#{pod}: Git tag source byte mismatch" unless downloaded.file? && Digest::SHA256.file(downloaded).hexdigest == digest
      [relative, digest]
    end
  end
  FileUtils.cp(lock_path, File.join(output, "#{name}-Podfile.lock.txt"))
  proof[:pods] << { name: name, version: spec.version.to_s, source: spec.source,
                    podspecSha256: Digest::SHA256.file(file).hexdigest,
                    lockSha256: Digest::SHA256.file(lock_path).hexdigest,
                    checkout: checkout, downloadedSourceFiles: source_files, compiler: validator.compiler_proof, result: 'passed' }
  File.write(File.join(output, 'proof.json'), JSON.pretty_generate(proof) + "\n")
end
fixture = 'verification-consumer/ios-native'
fixture_files = %w[Package.swift Sources/NativeConsumer.swift]
manifest = File.read(File.join(fixture, 'Package.swift'))
default_dependency = 'exact: "0.2.0-rc.1"'
abort 'Native fixture default dependency changed' unless manifest.scan(default_dependency).length == 1
if check_only
  puts 'Tagged iOS Pod and SwiftPM source/version contract passed; no public compilation'
  exit
end
# Keep the fixture's historical default; only the isolated release consumer pins this tag.
stage = Dir.mktmpdir('diagnostics-tagged-spm-', ENV.fetch('RUNNER_TEMP'))
fixture_hashes = fixture_files.to_h do |relative|
  original = File.binread(File.join(fixture, relative))
  tagged, error, status = Open3.capture3('git', 'show', "#{tag_sha}:#{fixture}/#{relative}")
  abort "SwiftPM fixture differs from tag: #{relative}: #{error}" unless status.success? && tagged.b == original
  target = File.join(stage, relative)
  FileUtils.mkdir_p(File.dirname(target))
  File.binwrite(target, relative == 'Package.swift' ? original.sub(default_dependency, "exact: \"#{version}\"") : original)
  [relative, { tagSha256: Digest::SHA256.hexdigest(original), consumerSha256: Digest::SHA256.file(target).hexdigest }]
end
packages = File.join(stage, 'source-packages')
command = ['xcodebuild', '-scheme', 'DiagnosticsNativeConsumer', '-destination', 'generic/platform=iOS Simulator',
           '-configuration', 'Debug', '-derivedDataPath', File.join(stage, 'derived-data'),
           '-clonedSourcePackagesDirPath', packages, 'build']
compiler_log = File.join(output, 'SwiftPM-compiler.log')
compiler_status = nil
File.open(compiler_log, 'w') do |log|
  Open3.popen2e(*command, chdir: stage) do |input, stream, process|
    input.close
    stream.each_line { |line| log.write(line); log.flush; $stdout.write(line); $stdout.flush }
    compiler_status = process.value
  end
end
compiler_output = File.read(compiler_log)
compile_rows = compiler_output.lines.select { |line| line.match?(/SwiftCompile|CompileSwift/) }
abort 'SwiftPM consumer compilation failed or skipped' unless compiler_status.success? && compiler_output.include?('** BUILD SUCCEEDED **') && compile_rows.any? { |line| line.include?('NativeConsumer.swift') }
locks = ['Package.resolved', '.swiftpm/xcode/package.xcworkspace/xcshareddata/swiftpm/Package.resolved'].map { |path| File.join(stage, path) }.select { |path| File.file?(path) }
abort 'SwiftPM consumer lock is missing' if locks.empty?
locks.each_with_index do |path, index|
  pins = JSON.parse(File.read(path)).fetch('pins')
  abort 'SwiftPM unexpected dependencies' unless pins.length == 1
  pin = pins.first
  abort 'SwiftPM lock tag/revision mismatch' unless pin['identity'] == 'diagnostics' && pin['location'] == repository && pin['state']['version'] == version && pin['state']['revision'] == tag_sha
  FileUtils.cp(path, File.join(output, "SwiftPM-Package-#{index}.resolved.txt"))
end
downloaded = File.join(packages, 'checkouts', 'diagnostics')
checkout_sha, error, status = Open3.capture3('git', '-C', downloaded, 'rev-parse', 'HEAD')
abort "SwiftPM checkout differs from tag: #{error}" unless status.success? && checkout_sha.strip == tag_sha
native_sources = Dir.glob(File.join(root, 'ios-support', '*.swift')).sort.to_h do |source|
  relative = Pathname(source).relative_path_from(Pathname(root)).to_s
  digest = Digest::SHA256.file(source).hexdigest
  abort "SwiftPM downloaded source differs: #{relative}" unless Digest::SHA256.file(File.join(downloaded, relative)).hexdigest == digest
  [relative, digest]
end
proof[:swiftPackage] = { version: version, revision: tag_sha, fixtureFiles: fixture_hashes, downloadedSourceFiles: native_sources,
                         command: command, compilerLogSha256: Digest::SHA256.file(compiler_log).hexdigest,
                         ownConsumerCompiled: 'NativeConsumer.swift', locksChecked: locks.length, result: 'passed' }
File.write(File.join(output, 'proof.json'), JSON.pretty_generate(proof) + "\n")
exit
