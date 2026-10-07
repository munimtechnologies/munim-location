require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "MunimLocation"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"]
  s.license      = package["license"]
  s.authors      = package["author"]

  s.platforms    = { :ios => min_ios_version_supported }
  s.source       = { :git => "https://github.com/munimtechnologies/munim-location.git", :tag => "#{s.version}" }

  s.source_files = [
    # Implementation (Swift)
    "ios/**/*.{swift}",
    # Launch observer (Objective-C)
    "ios/**/*.{m,mm}",
  ]

  s.frameworks = "CoreLocation", "CoreMotion", "MapKit", "Contacts"

  autolinking_script = File.join(__dir__, "nitrogen/generated/ios/MunimLocation+autolinking.rb")
  if File.exist?(autolinking_script)
    load autolinking_script
    add_nitrogen_files(s)
  else
    Pod::UI.puts "[MunimLocation] Skipping Nitro autolinking – #{autolinking_script} not found"
  end

  s.dependency 'React-jsi'
  s.dependency 'React-callinvoker'
  s.dependency 'NitroModules'
  install_modules_dependencies(s)
end
