package net.lecousin.ant.maven;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.maven.Maven;
import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;

import net.lecousin.ant.core.manifest.ConnectorManifest;
import net.lecousin.ant.maven.artifact.ArtifactReader;
import net.lecousin.ant.maven.artifact.DirectoryArtifactReader;
import net.lecousin.ant.maven.artifact.JarArtifactReader;

@Mojo(name = "generate-integration-tests", defaultPhase = LifecyclePhase.TEST, requiresDependencyResolution = ResolutionScope.TEST)
public class GenerateIntegrationTestsMojo extends AbstractMojo {

	private static final String GENERATION_PATH_PROPERTY = "integration-tests-path";
	
	@Parameter(required = false, name = GENERATION_PATH_PROPERTY)
	private String integrationTestsPath;
	
	@Component
	private MavenProject project;
	
	@Component
	private MavenSession session;
	
	@Component
	private ProjectBuilder projectBuilder;
	
	@Component
	private Maven maven;
	
	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		getLog().info("Analyzing dependencies...");
		analyzeDependencies();
		List<List<Test>> combinations = buildTestCombinations();
		if (combinations.isEmpty()) {
			getLog().warn("No integration test found");
			return;
		}
		
		File integrationTestsPath = createIntegrationTestsDirectory();
		getLog().info("Creating test projects into " + integrationTestsPath.toString());
		try {
			createProject(integrationTestsPath, combinations);
		} catch (IOException e) {
			throw new MojoFailureException("Error creating test projects", e);
		}
	}
	
	private Map<String, Map<String, Artifact>> connectors = new HashMap<>();

	private void analyzeDependencies() {
		for (Artifact a : project.getArtifacts()) {
			ConnectorManifest manifest = getConnectorManifest(a);
			if (manifest == null) continue;
			getLog().info(" + Dependency found on connector type " + manifest.getName());
			getLog().info("   + Connector type " + manifest.getName() + ": implementation " + manifest.getImpl());
			connectors.computeIfAbsent(manifest.getName(), k -> new HashMap<>())
			.put(manifest.getImpl(), a);
			MavenProject connectorProject;
			try {
				connectorProject = getProject(a);
			} catch (Exception e) {
				getLog().error("Cannot get project from " + a);
				continue;
			}
			MavenProject connectorParent = connectorProject.getParent();
			for (String module : connectorParent.getModules()) {
				if (module.equals(a.getArtifactId())) continue;
				Artifact moduleArtifact = new DefaultArtifact(a.getGroupId(), module, a.getVersion(), "compile", "jar", "", a.getArtifactHandler());
				MavenProject moduleProject;
				try {
					moduleProject = getProject(moduleArtifact);
				} catch (Exception e) {
					continue;
				}

				ConnectorManifest moduleManifest = null;
				if (moduleProject.getArtifact().getFile() != null)
					moduleManifest = getConnectorManifest(moduleProject.getArtifact());
				if (moduleManifest == null && moduleProject.getResources() != null)
					for (Resource r : moduleProject.getResources()) {
						if (r.getDirectory() != null)
							moduleManifest = getConnectorManifest(new DirectoryArtifactReader(new File(r.getDirectory())));
						if (moduleManifest != null) break;
					}
				if (moduleManifest == null) {
					File jar = downloadJar(moduleArtifact);
					if (jar != null)
						try {
							moduleManifest = getConnectorManifest(new JarArtifactReader(jar));
						} catch (IOException e) {
						}
				}

				if (moduleManifest != null) {
					getLog().info("   + Connector type " + moduleManifest.getName() + ": implementation " + moduleManifest.getImpl());
					MavenProject p = moduleProject;
					connectors.computeIfAbsent(moduleManifest.getName(), k -> new HashMap<>())
					.computeIfAbsent(moduleManifest.getImpl(), k -> p.getArtifact());
				}
			}
		}
	}
	
	private MavenProject getProject(Artifact a) throws ProjectBuildingException {
		ProjectBuildingRequest request = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
		request.setProject(null);
		return projectBuilder.build(a, request).getProject();
	}
	
	private ConnectorManifest getConnectorManifest(Artifact a) {
		try (ArtifactReader reader = ArtifactReader.of(a)) {
			return getConnectorManifest(reader);
		} catch (Exception e) {
			getLog().warn("Error accessing to artifact " + a.getFile(), e);
		}
		return null;
	}
	
	private ConnectorManifest getConnectorManifest(ArtifactReader reader) {
		try {
			if (!reader.hasFile("META-INF/connector.yaml")) return null;
			ConnectorManifest manifest = null;
			try (InputStream input = reader.openFile("META-INF/connector.yaml")) {
				manifest = ConnectorManifest.load(input);
			} catch (Exception e) {
				getLog().warn("Cannot read connector manifest", e);
			}
			return manifest;
		} catch (Exception e) {
			getLog().warn("Error reading connector manifest", e);
		}
		return null;
	}
	
	private static class Test {
		String type;
		String name;
		Collection<Artifact> possibilities;
		Artifact possibilityToTest;
	}
	
	private List<List<Test>> buildTestCombinations() {
		List<List<Test>> connectorsTests = listConnectorsTests();
		return buildCombinations(connectorsTests);
	}
	
	// List of connector type -> List of connector implementation to test
	private List<List<Test>> listConnectorsTests() {
		List<List<Test>> tests = new LinkedList<>();
		for (Map.Entry<String, Map<String, Artifact>> connectorType : connectors.entrySet()) {
			String typeName = connectorType.getKey();
			Collection<Artifact> possibilities = connectorType.getValue().values();
			List<Test> connectorTypeTests = new LinkedList<>();
			for (Map.Entry<String, Artifact> connectorImpl : connectorType.getValue().entrySet()) {
				String impl = connectorImpl.getKey();
				Artifact implArtifact = connectorImpl.getValue();
				Test tc = new Test();
				tc.type = "connector";
				tc.name = typeName + "-" + impl;
				tc.possibilities = possibilities;
				tc.possibilityToTest = implArtifact;
				connectorTypeTests.add(tc);
			}
			tests.add(connectorTypeTests);
		}
		return tests;
	}
	
	private <T> List<List<T>> buildCombinations(List<List<T>> tests) {
		if (tests.isEmpty()) return List.of();
		// take one from each time, and loop
		List<T> firstType = tests.get(0);
		List<List<T>> combinations = new LinkedList<>();
		for (var test : firstType) combinations.add(List.of(test));
		for (int i = 1; i < tests.size(); ++i)
			combinations = combine(combinations, tests.get(i));
		return combinations;
	}
	
	private <T> List<List<T>> combine(List<List<T>> list, List<T> add) {
		List<List<T>> result = new LinkedList<>();
		for (var currentTestCase : list) {
			for (var newTestCase : add) {
				List<T> combine = new LinkedList<>(currentTestCase);
				combine.add(newTestCase);
				result.add(combine);
			}
		}
		return result;
	}
	
	private File createIntegrationTestsDirectory() throws MojoFailureException {
		if (session.getUserProperties().containsKey(GENERATION_PATH_PROPERTY)) {
			String path = (String) session.getUserProperties().get(GENERATION_PATH_PROPERTY);
			if (path != null && !path.isBlank())
				try {
					return Files.createDirectory(Path.of(path)).toFile();
				} catch (Exception e) {
					getLog().warn("Cannot create generation directory " + path);
				}
		}
		if (integrationTestsPath != null && !integrationTestsPath.isBlank())
			try {
				return Files.createDirectory(Path.of(integrationTestsPath)).toFile();
			} catch (Exception e) {
				getLog().warn("Cannot create generation directory " + integrationTestsPath);
			}
		try {
			return Files.createTempDirectory("test").toFile();
		} catch (IOException e) {
			throw new MojoFailureException("Cannot create temp directory", e);
		}
	}
	
	private void createProject(File targetDir, List<List<Test>> combinations) throws IOException {
		File srcDir = project.getParentFile().getParentFile();
		getLog().info(" + Copy parent project with modules from " + srcDir.toString() + " to " + targetDir.toString());
		List<String> exclusions = new LinkedList<>();
		exclusions.add("jacoco-aggregate-report");
		exclusions.add(project.getArtifactId());
		exclusions.add(".git");
		if (targetDir.getParentFile().getCanonicalPath().equals(srcDir.getCanonicalPath()))
			exclusions.add(targetDir.getName());
		copyContent(srcDir, targetDir, exclusions);
		generateTestProjects(combinations, targetDir);
		List<String> modules = getModules(targetDir);
		getLog().info(" + Generate jacoco-aggregate-report project");
		generateJacocoAggregateReportProject(targetDir, modules);
		modules.add("jacoco-aggregate-report");
		getLog().info(" + Update parent pom");
		overwriteModules(new File(targetDir, "pom.xml"), modules);
	}
	
	private void copyContent(File from, File to, List<String> exclude) throws IOException {
		for (var file : from.listFiles()) {
			if (file.getName().equals(".") || file.getName().equals("..") || exclude.contains(file.getName())) continue;
			copy(file, to);
		}
	}
	
	private void copy(File file, File to) throws IOException {
		if (file.isDirectory()) {
			File target = new File(to, file.getName());
			target.mkdir();
			copyContent(file, target, List.of());
		} else {
			Files.copy(file.toPath(), new File(to, file.getName()).toPath());
		}
	}
	
	private void generateTestProjects(List<List<Test>> combinations, File to) throws IOException {
		for (var testCase : combinations)
			generateTestProject(testCase, to);
	}
	
	private void generateTestProject(List<Test> testCase, File to) throws IOException {
		StringBuilder name = new StringBuilder();
		name.append(project.getArtifactId()).append("-with-");
		for (Test test : testCase) {
			name.append(test.type).append('-').append(test.name);
		}
		getLog().info(" + Generate project " + name);
		File dir = new File(to, name.toString());
		dir.mkdir();
		copyContent(project.getFile().getParentFile(), dir, List.of("pom.xml"));
		generateTestProjectPom(project.getFile(), new File(dir, "pom.xml"), name.toString(), testCase);
	}
	
	private void generateTestProjectPom(File sourcePomFile, File targetPomFile, String newArtifactId, List<Test> testCase) throws IOException {
		String pom = Files.readString(sourcePomFile.toPath());
		for (Test test : testCase) {
			boolean found = false;
			for (Artifact possibleImplementation : test.possibilities) {
				String result = replaceArtifactId(possibleImplementation.getArtifactId(), test.possibilityToTest.getArtifactId(), pom);
				if (result != null) {
					pom = result;
					found = true;
					break;
				}
			}
			if (!found) throw new IOException("Cannot find dependency for " + test.type + " " + test.name);
		}
		pom = replaceArtifactId(project.getArtifactId(), newArtifactId, pom);
		if (pom == null) throw new IOException("Cannot find artifactId in pom: " + project.getArtifactId());
		Files.writeString(targetPomFile.toPath(), pom);
	}
	
	private String replaceArtifactId(String previousName, String newName, String pom) {
		int i = pom.indexOf(previousName);
		int before = pom.lastIndexOf("<artifactId>", i);
		if (before > 0 && pom.substring(before, i).trim().equals("<artifactId>")) {
			int after = pom.indexOf("</artifactId>", i + previousName.length());
			if (after > 0 && pom.substring(i + previousName.length(), after).trim().isBlank()) {
				// found it
				pom = pom.substring(0, i) + newName + pom.substring(i + previousName.length());
				return pom;
			}
		}
		return null;
	}

	private List<String> getModules(File root) {
		List<String> modules = new LinkedList<>();
		for (var file : root.listFiles()) {
			if (file.getName().equals(".") || file.getName().equals("..") || !file.isDirectory()) continue;
			File pomFile = new File(file, "pom.xml");
			if (pomFile.exists()) modules.add(file.getName());
		}
		return modules;
	}
	
	private void overwriteModules(File pomFile, List<String> modules) throws IOException {
		String pom = Files.readString(pomFile.toPath());
		int start = pom.indexOf("<modules>") + 9;
		int end = pom.indexOf("</modules>", start);
		StringBuilder s = new StringBuilder(pom.length() + modules.size() * 256);
		s.append(pom.substring(0, start));
		for (String module : modules)
			s.append("<module>").append(module).append("</module>");
		s.append(pom.substring(end));
		Files.writeString(pomFile.toPath(), s.toString());
	}
	
	private void generateJacocoAggregateReportProject(File root, List<String> modules) throws IOException {
		File dir = new File(root, "jacoco-aggregate-report");
		dir.mkdir();
		File pomFile = new File(dir, "pom.xml");
		String content;
		try (var input = getClass().getClassLoader().getResourceAsStream("jacoco-aggregate-report.pom")) {
			content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
		StringBuilder dependencies = new StringBuilder(modules.size() * 256);
		for (String module : modules) {
			dependencies.append("<dependency><groupId>${project.groupId}</groupId><artifactId>")
			.append(module)
			.append("</artifactId><version>${project.version}</version></dependency>");
		}
		content = content.replace("$${{groupId}}$$", project.getParentArtifact().getGroupId())
			.replace("$${{parentArtifactId}}$$", project.getParentArtifact().getArtifactId())
			.replace("$${{version}}$$", project.getParentArtifact().getVersion())
			.replace("$${{dependencies}}$$", dependencies.toString());
		Files.writeString(pomFile.toPath(), content);
	}
	
	private File downloadJar(Artifact a) {
		MavenExecutionRequest request = DefaultMavenExecutionRequest.copy(session.getRequest());
		request.setGoals(List.of("org.apache.maven.plugins:maven-dependency-plugin:3.6.1:get"));
		Properties props = new Properties();
		props.setProperty("groupId", a.getGroupId());
		props.setProperty("artifactId", a.getArtifactId());
		props.setProperty("version", a.getVersion());
		request.setUserProperties(props);
		maven.execute(request).hasExceptions();
		Artifact a2 = session.getLocalRepository().find(a);
		if (a2 != null && a2.getFile() != null) return a2.getFile();
		String path = session.getRepositorySession().getLocalRepositoryManager().getPathForLocalArtifact(RepositoryUtils.toArtifact(a));
		if (path != null) {
			File f = new File(path);
			if (f.exists())
				return f;
		}
		return null;
	}
}
