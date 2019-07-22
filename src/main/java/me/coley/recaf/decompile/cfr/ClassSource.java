package me.coley.recaf.decompile.cfr;

import me.coley.recaf.util.ClassUtil;
import me.coley.recaf.workspace.Workspace;
import org.benf.cfr.reader.api.ClassFileSource;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair;

import java.util.*;

/**
 * CFR class source. Provides access to workspace clases.
 *
 * @author Matt
 */
public class ClassSource implements ClassFileSource {
	private final Workspace workspace;

	/**
	 * Constructs a CFR class source.
	 *
	 * @param workspace
	 * 		Workspace to pull classes from.
	 */
	public ClassSource(Workspace workspace) {
		this.workspace = workspace;
	}

	@Override
	public void informAnalysisRelativePathDetail(String usePath, String specPath) {}

	@Override
	public Collection<String> addJar(String jarPath) {
		return Collections.emptySet();
	}

	@Override
	public String getPossiblyRenamedPath(String path) {
		return path;
	}

	@Override
	@SuppressWarnings("deprecation")
	public Pair<byte[], String> getClassFileContent(String inputPath) {
		String className = inputPath.substring(0, inputPath.indexOf(".class"));
		byte[] code;
		if(workspace.hasClass(className))
			code = workspace.getRawClass(className);
		else
			code = Objects.requireNonNull(ClassUtil.fromRuntime(className),
						"Failed to load class from runtime: " + className).b;
		return new Pair<>(code, inputPath);
	}
}
