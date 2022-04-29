package me.coley.recaf.ui.dialog;

import dev.xdark.ssvm.memory.MemoryManager;
import dev.xdark.ssvm.mirror.InstanceJavaClass;
import dev.xdark.ssvm.mirror.JavaClass;
import dev.xdark.ssvm.util.VMHelper;
import dev.xdark.ssvm.util.VMPrimitives;
import dev.xdark.ssvm.util.VMSymbols;
import dev.xdark.ssvm.value.*;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import me.coley.recaf.code.CommonClassInfo;
import me.coley.recaf.code.MethodInfo;
import me.coley.recaf.ssvm.SsvmIntegration;
import me.coley.recaf.ssvm.value.ConstNumericValue;
import me.coley.recaf.ssvm.value.ConstStringValue;
import me.coley.recaf.ui.util.Icons;
import me.coley.recaf.ui.util.Lang;
import me.coley.recaf.util.AccessFlag;
import me.coley.recaf.util.ErrorableConsumer;
import me.coley.recaf.util.StringUtil;
import me.coley.recaf.util.Types;
import me.coley.recaf.util.logging.Logging;
import me.coley.recaf.util.threading.FxThreadUtil;
import me.coley.recaf.util.threading.ThreadUtil;
import org.objectweb.asm.Type;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Basic SSVM method caller dialog.
 *
 * @author Matt Coley
 */
public class SsvmInvokeCallDialog extends ClosableDialog {
	private static final Logger logger = Logging.get(SsvmInvokeCallDialog.class);
	private final BooleanProperty totality = new SimpleBooleanProperty();
	private final List<InputWrapper> inputs = new ArrayList<>();
	private final SsvmIntegration ssvm;
	private final VMHelper helper;
	private final VMSymbols symbols;
	private final VMPrimitives primitives;
	private final MemoryManager memory;
	private final Value[] values;

	/**
	 * @param owner
	 * 		Method owner.
	 * @param info
	 * 		Method info.
	 * @param ssvm
	 * 		SSVM integration service.
	 */
	public SsvmInvokeCallDialog(CommonClassInfo owner, MethodInfo info, SsvmIntegration ssvm) {
		super(Lang.getBinding("dialog.title.vm-invoke-args"),
				new StringBinding() {
					@Override
					protected String computeValue() {
						return StringUtil.shortenPath(owner.getName()) + "." + info.getName() + info.getDescriptor();
					}
				},
				Icons.getMethodIcon(info));
		this.ssvm = ssvm;
		helper = ssvm.getVm().getHelper();
		symbols = ssvm.getVm().getSymbols();
		primitives = ssvm.getVm().getPrimitives();
		memory = ssvm.getVm().getMemoryManager();
		grid.addRow(0, new Label("Parameter"), new Label("Type"), new Label("Editor"));
		Type methodType = Type.getMethodType(info.getDescriptor());
		Type[] methodArgs = methodType.getArgumentTypes();
		boolean isStatic = AccessFlag.isStatic(info.getAccess());
		int argSlot = isStatic ? 0 : 1;
		for (int i = 0; i < methodArgs.length; i++) {
			Type arg = methodArgs[i];
			Label index = new Label(String.valueOf(i));
			Label type = new Label(arg.getDescriptor());
			InputWrapper input;
			switch (arg.getSort()) {
				case Type.BOOLEAN:
				case Type.CHAR:
				case Type.BYTE:
				case Type.SHORT:
				case Type.INT:
					input = intEditor();
					break;
				case Type.FLOAT:
					input = floatEditor();
					break;
				case Type.LONG:
					input = longEditor();
					break;
				case Type.DOUBLE:
					input = doubleEditor();
					break;
				case Type.ARRAY:
					input = arrayEditor(arg);
					break;
				case Type.OBJECT:
					input = objectEditor(arg);
					break;
				default:
					throw new IllegalStateException("Unsupported arg type: " + arg);
			}
			input.slot = argSlot;
			inputs.add(input);
			grid.addRow(i + 1, index, type, input.editor);
			argSlot += arg.getSize();
		}
		values = new Value[argSlot];
		if (!isStatic) {
			JavaClass type = helper.tryFindClass(symbols.java_lang_Object.getClassLoader(), owner.getName(), true);
			InstanceValue instance = memory.newInstance((InstanceJavaClass) type);
			helper.initializeDefaultValues(instance);
			values[0] = instance;
		}
		TextArea output = new TextArea();
		Button runButton = new Button();
		runButton.textProperty().bind(Lang.getBinding("dialog.vm.execute"));
		runButton.setGraphic(Icons.getIconView(Icons.PLAY));
		runButton.setDisable(!validInputs());
		runButton.setOnMousePressed(e -> {
			// Only create values when confirm button pressed
			for (InputWrapper input : inputs) {
				Value value = input.supplier.get();
				values[input.slot] = value;
				if (value.isWide()) {
					values[input.slot + 1] = TopValue.INSTANCE;
				}
			}
			// Run and get result
			Future<SsvmIntegration.VmRunResult> resultFuture = ssvm.runMethod(owner, info, getValues());
			ThreadUtil.run(() -> {
				SsvmIntegration.VmRunResult result = null;
				try {
					result = resultFuture.get(1, TimeUnit.MINUTES);
				} catch (InterruptedException ex) {
					logger.error("Invoke future thread interrupted", ex);
					return;
				} catch (ExecutionException ex) {
					logger.error("Invoke future thread encountered unhandled error", ex.getCause());
					return;
				} catch (TimeoutException ex) {
					logger.error("Invoke future thread timed out", ex);
					return;
				}
				if (result.hasError()) {
					Exception ex = ssvm.unwrap(result.getException());
					FxThreadUtil.run(() -> {
						output.setText(StringUtil.traceToString(ex));
						output.setStyle("-fx-text-fill: red;");
					});
				} else {
					String resultText = ssvm.toString(result.getValue());
					FxThreadUtil.run(() -> {
						output.setStyle(null);
						output.setText(resultText);
					});
				}
			});
		});
		runButton.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
		GridPane.setFillWidth(runButton, true);
		grid.add(runButton, 0, methodArgs.length + 1, 3, 1);
		grid.add(output, 0, methodArgs.length + 2, 3, 1);
		// Run state tied to validity
		totality.addListener((observable, oldValue, newValue) -> runButton.setDisable(!newValue));
	}

	/**
	 * @return {@code true} when all inputs are valid.
	 */
	public boolean validInputs() {
		return inputs.stream().allMatch(i -> i.valid.getAsBoolean());
	}

	/**
	 * @return Last array of input parameter values.
	 */
	public Value[] getValues() {
		return values;
	}

	/**
	 * @return Editor for integers, shorts, chars, bytes, and booleans.
	 */
	private InputWrapper intEditor() {
		BooleanProperty valid = new SimpleBooleanProperty(true);
		TextField field = new TextField("0");
		field.textProperty().addListener((observable, oldValue, newValue) -> handle(valid, newValue, Integer::parseInt));
		return new InputWrapper(valid::get, field, () -> ConstNumericValue.ofInt(Integer.parseInt(field.getText())));
	}

	/**
	 * @return Editor for floats.
	 */
	private InputWrapper floatEditor() {
		BooleanProperty valid = new SimpleBooleanProperty(true);
		TextField field = new TextField("0");
		field.textProperty().addListener((observable, oldValue, newValue) -> handle(valid, newValue, Float::parseFloat));
		return new InputWrapper(valid::get, field, () -> ConstNumericValue.ofFloat(Float.parseFloat(field.getText())));
	}

	/**
	 * @return Editor for longs.
	 */
	private InputWrapper longEditor() {
		BooleanProperty valid = new SimpleBooleanProperty(true);
		TextField field = new TextField("0");
		field.textProperty().addListener((observable, oldValue, newValue) -> handle(valid, newValue, Long::parseLong));
		return new InputWrapper(valid::get, field, () -> ConstNumericValue.ofLong(Long.parseLong(field.getText())));
	}

	/**
	 * @return Editor for doubles.
	 */
	private InputWrapper doubleEditor() {
		BooleanProperty valid = new SimpleBooleanProperty(true);
		TextField field = new TextField("0.0");
		field.textProperty().addListener((observable, oldValue, newValue) -> handle(valid, newValue, Double::parseDouble));
		return new InputWrapper(valid::get, field, () -> ConstNumericValue.ofDouble(Double.parseDouble(field.getText())));
	}

	/**
	 * @param type
	 * 		Array type.
	 *
	 * @return Editor for the array type.
	 */
	private InputWrapper arrayEditor(Type type) {
		BooleanProperty valid = new SimpleBooleanProperty(true);
		Type element = type.getElementType();
		switch (element.getSort()) {
			case Type.BOOLEAN:
			case Type.CHAR:
			case Type.BYTE:
			case Type.SHORT:
			case Type.INT: {
				TextField field = new TextField("1, 2, 3");
				field.textProperty().addListener((observable, oldValue, newValue) -> handle(valid, newValue, text -> {
					for (String part : text.split("\\s+,\\s+"))
						Integer.parseInt(part);
				}));
				return new InputWrapper(field, () -> {
					if (field.getText().isBlank())
						switch (element.getSort()) {
							case Type.BOOLEAN:
								return helper.emptyArray(primitives.booleanPrimitive);
							case Type.CHAR:
								return helper.emptyArray(primitives.charPrimitive);
							case Type.BYTE:
								return helper.emptyArray(primitives.bytePrimitive);
							case Type.SHORT:
								return helper.emptyArray(primitives.shortPrimitive);
							case Type.INT:
								return helper.emptyArray(primitives.intPrimitive);
						}
					String[] args = field.getText().split("\\s*,\\s*");
					int count = args.length;
					ArrayValue array;
					switch (element.getSort()) {
						case Type.BOOLEAN:
							array = helper.newArray(primitives.booleanPrimitive, count);
							for (int i = 0; i < count; i++)
								array.setBoolean(i, Integer.parseInt(args[i]) != 0);
							break;
						case Type.CHAR:
							array = helper.newArray(primitives.charPrimitive, count);
							for (int i = 0; i < count; i++)
								array.setChar(i, (char) Integer.parseInt(args[i]));
							break;
						case Type.BYTE:
							array = helper.newArray(primitives.bytePrimitive, count);
							for (int i = 0; i < count; i++)
								array.setByte(i, (byte) Integer.parseInt(args[i]));
							break;
						case Type.SHORT:
							array = helper.newArray(primitives.shortPrimitive, count);
							for (int i = 0; i < count; i++)
								array.setShort(i, (short) Integer.parseInt(args[i]));
							break;
						case Type.INT:
							array = helper.newArray(primitives.intPrimitive, count);
							for (int i = 0; i < count; i++)
								array.setInt(i, Integer.parseInt(args[i]));
							break;
						default:
							throw new IllegalStateException("Unsupported 'int' type: " + element.getInternalName());
					}
					return array;
				});
			}
			case Type.FLOAT: {
				TextField field = new TextField("1, 2, 3");
				field.textProperty().addListener((observable, oldValue, newValue) -> handle(valid, newValue, text -> {
					for (String part : text.split("\\s+,\\s+"))
						Float.parseFloat(part);
				}));
				return new InputWrapper(field, () -> {
					if (field.getText().isBlank())
						return helper.emptyArray(primitives.floatPrimitive);
					String[] args = field.getText().split("\\s*,\\s*");
					int count = args.length;
					ArrayValue array = helper.newArray(primitives.floatPrimitive, count);
					for (int i = 0; i < count; i++)
						array.setFloat(i, Float.parseFloat(args[i]));
					return array;
				});
			}
			case Type.LONG: {
				TextField field = new TextField("1, 2, 3");
				field.textProperty().addListener((observable, oldValue, newValue) -> handle(valid, newValue, text -> {
					for (String part : text.split("\\s+,\\s+"))
						Long.parseLong(part);
				}));
				return new InputWrapper(field, () -> {
					if (field.getText().isBlank())
						return helper.emptyArray(primitives.longPrimitive);
					String[] args = field.getText().split("\\s*,\\s*");
					int count = args.length;
					ArrayValue array = helper.newArray(primitives.longPrimitive, count);
					for (int i = 0; i < count; i++)
						array.setLong(i, Long.parseLong(args[i]));
					return array;
				});
			}
			case Type.DOUBLE: {
				TextField field = new TextField("1.0, 2.0, 3.0");
				field.textProperty().addListener((observable, oldValue, newValue) -> handle(valid, newValue, text -> {
					for (String part : text.split("\\s+,\\s+"))
						Double.parseDouble(part);
				}));
				return new InputWrapper(field, () -> {
					if (field.getText().isBlank())
						return helper.emptyArray(primitives.doublePrimitive);
					String[] args = field.getText().split("\\s*,\\s*");
					int count = args.length;
					ArrayValue array = helper.newArray(primitives.doublePrimitive, count);
					for (int i = 0; i < count; i++)
						array.setDouble(i, Double.parseDouble(args[i]));
					return array;
				});
			}
			case Type.OBJECT: {
				if (Types.STRING_TYPE.equals(element)) {
					TextField field = new TextField("\"one\", \"two\", \"three\"");
					return new InputWrapper(field, () -> {
						if (field.getText().isBlank())
							return helper.emptyArray(symbols.java_lang_String);
						String[] args = field.getText().split("^\"|\"\\s*,\\s*\"|\"$");
						int count = args.length;
						ArrayValue array = helper.newArray(symbols.java_lang_String, count);
						for (int i = 0; i < count; i++)
							array.setValue(i, helper.newUtf8(args[i]));
						return array;
					});
				}
			}
			default:
				Label field = new Label("null");
				return new InputWrapper(field, () -> NullValue.INSTANCE);
		}
	}

	/**
	 * @param type
	 * 		Object type.
	 *
	 * @return Editor for the type.
	 */
	private InputWrapper objectEditor(Type type) {
		if (Types.STRING_TYPE.equals(type)) {
			TextField field = new TextField("string_text");
			return new InputWrapper(field, () -> ConstStringValue.ofString(helper, field.getText()));
		}
		CheckBox checkCreateDefault = new CheckBox();
		checkCreateDefault.selectedProperty().addListener((observable, oldValue, newValue) -> {
			if (newValue) {
				checkCreateDefault.textProperty().bind(Lang.getBinding("dialog.vm.create-dummy"));
			} else {
				checkCreateDefault.textProperty().bind(Lang.getBinding("dialog.vm.create-null"));
			}
		});
		checkCreateDefault.setSelected(true);
		return new InputWrapper(checkCreateDefault, () -> {
			if (checkCreateDefault.isSelected()) {
				InstanceJavaClass cls = (InstanceJavaClass) ssvm.getVm().findBootstrapClass(type.getInternalName(), true);
				if (cls != null)
					return memory.newInstance(cls);
			}
			return NullValue.INSTANCE;
		});
	}

	/**
	 * Updates {@link #totality} validity.
	 *
	 * @param valid
	 * 		Validity property of the editor.
	 * @param input
	 * 		Text input on text edit.
	 * @param action
	 * 		Action to run, throwing an exception if invalid.
	 */
	private void handle(BooleanProperty valid, String input, ErrorableConsumer<String> action) {
		try {
			action.accept(input);
			valid.set(true);
		} catch (Throwable t) {
			valid.set(false);
		}
		totality.set(validInputs());
	}

	private static class InputWrapper {
		private final Node editor;
		private final Supplier<Value> supplier;
		private final BooleanSupplier valid;
		private int slot;


		public InputWrapper(BooleanSupplier valid, Node editor, Supplier<Value> supplier) {
			this.valid = valid;
			this.editor = editor;
			this.supplier = supplier;
		}

		public InputWrapper(Node editor, Supplier<Value> supplier) {
			this(() -> true, editor, supplier);
		}
	}
}