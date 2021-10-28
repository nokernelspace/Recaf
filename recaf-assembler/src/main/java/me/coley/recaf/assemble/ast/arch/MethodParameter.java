package me.coley.recaf.assemble.ast.arch;

import me.coley.recaf.assemble.ast.Named;
import me.coley.recaf.assemble.ast.BaseElement;
import me.coley.recaf.assemble.ast.Descriptor;

/**
 * Part of a {@link MethodParameters}.
 *
 * @author Matt Coley
 */
public class MethodParameter extends BaseElement implements Named, Descriptor {
	private final String type;
	private final String name;

	/**
	 * @param type
	 * 		Parameter descriptor.
	 * @param name
	 * 		Parameter name.
	 */
	public MethodParameter(String type, String name) {
		this.type = type;
		this.name = name;
	}

	@Override
	public String getDesc() {
		return type;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public String print() {
		return type + " " + name;
	}
}
