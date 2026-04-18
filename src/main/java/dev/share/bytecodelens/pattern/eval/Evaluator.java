package dev.share.bytecodelens.pattern.eval;

import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.LoadedJar;
import dev.share.bytecodelens.pattern.ast.AccessPredicate;
import dev.share.bytecodelens.pattern.ast.AnnotationPredicate;
import dev.share.bytecodelens.pattern.ast.ClassPattern;
import dev.share.bytecodelens.pattern.ast.ContainsPredicate;
import dev.share.bytecodelens.pattern.ast.DescPredicate;
import dev.share.bytecodelens.pattern.ast.ExtendsPredicate;
import dev.share.bytecodelens.pattern.ast.FieldCountPredicate;
import dev.share.bytecodelens.pattern.ast.FieldPattern;
import dev.share.bytecodelens.pattern.ast.ImplementsPredicate;
import dev.share.bytecodelens.pattern.ast.InstructionCountPredicate;
import dev.share.bytecodelens.pattern.ast.InstructionMatcher;
import dev.share.bytecodelens.pattern.ast.MatchSpec;
import dev.share.bytecodelens.pattern.ast.MethodCountPredicate;
import dev.share.bytecodelens.pattern.ast.MethodPattern;
import dev.share.bytecodelens.pattern.ast.NamePredicate;
import dev.share.bytecodelens.pattern.ast.NestedPattern;
import dev.share.bytecodelens.pattern.ast.NotPredicate;
import dev.share.bytecodelens.pattern.ast.OrPredicate;
import dev.share.bytecodelens.pattern.ast.Pattern;
import dev.share.bytecodelens.pattern.ast.Predicate;
import dev.share.bytecodelens.pattern.ast.Quantifier;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Evaluator {

    private static final Logger log = LoggerFactory.getLogger(Evaluator.class);

    public List<PatternResult> evaluate(LoadedJar jar, Pattern pattern) {
        if (jar == null || pattern == null) return List.of();
        List<PatternResult> out = Collections.synchronizedList(new ArrayList<>());
        jar.classes().parallelStream().forEach(c -> {
            try {
                ClassNode node = readClassNode(c);
                evalAgainstClass(c, node, pattern, out);
            } catch (Exception ex) {
                log.debug("Skip {}: {}", c.name(), ex.getMessage());
            }
        });
        return List.copyOf(out);
    }

    private void evalAgainstClass(ClassEntry entry, ClassNode node, Pattern p, List<PatternResult> out) {
        switch (p) {
            case ClassPattern cp -> {
                if (matchesClass(node, cp.predicates())) {
                    out.add(new PatternResult(PatternResult.Kind.CLASS,
                            entry.name(), null, null, ""));
                }
            }
            case MethodPattern mp -> {
                if (node.methods == null) return;
                for (MethodNode m : node.methods) {
                    if (matchesMethod(m, node, mp.predicates())) {
                        out.add(new PatternResult(PatternResult.Kind.METHOD,
                                entry.name(), m.name, m.desc, ""));
                    }
                }
            }
            case FieldPattern fp -> {
                if (node.fields == null) return;
                for (FieldNode f : node.fields) {
                    if (matchesField(f, node, fp.predicates())) {
                        out.add(new PatternResult(PatternResult.Kind.FIELD,
                                entry.name(), f.name, f.desc, ""));
                    }
                }
            }
        }
    }

    private boolean matchesClass(ClassNode node, List<Predicate> preds) {
        for (Predicate p : preds) if (!evalClassPredicate(node, p)) return false;
        return true;
    }

    private boolean evalClassPredicate(ClassNode node, Predicate p) {
        return switch (p) {
            case NamePredicate np -> np.spec().matches(node.name.replace('/', '.'))
                    || np.spec().matches(node.name);
            case AccessPredicate ap -> hasClassAccess(node.access, ap.flag());
            case ExtendsPredicate ep -> node.superName != null
                    && (ep.spec().matches(node.superName) || ep.spec().matches(node.superName.replace('/', '.')));
            case ImplementsPredicate ip -> node.interfaces != null
                    && node.interfaces.stream().anyMatch(i -> ip.spec().matches(i) || ip.spec().matches(i.replace('/', '.')));
            case AnnotationPredicate ap -> hasAnnotation(node.visibleAnnotations, ap.spec())
                    || hasAnnotation(node.invisibleAnnotations, ap.spec());
            case MethodCountPredicate mc -> mc.op().apply(
                    node.methods == null ? 0 : node.methods.size(), mc.value());
            case FieldCountPredicate fc -> fc.op().apply(
                    node.fields == null ? 0 : node.fields.size(), fc.value());
            case NestedPattern np -> evalNested(node, np);
            case OrPredicate op -> op.alternatives().stream().anyMatch(a -> evalClassPredicate(node, a));
            case NotPredicate np -> !evalClassPredicate(node, np.inner());
            case DescPredicate dp -> false;
            case ContainsPredicate cp -> false;
            case InstructionCountPredicate icp -> false;
        };
    }

    private boolean evalNested(ClassNode node, NestedPattern np) {
        List<Predicate> inner = np.inner().predicates();
        Quantifier q = np.quantifier();
        if (np.inner() instanceof MethodPattern) {
            if (node.methods == null) return q == Quantifier.NONE;
            int total = node.methods.size();
            long matches = node.methods.stream().filter(m -> matchesMethod(m, node, inner)).count();
            return switch (q) {
                case ANY -> matches > 0;
                case ALL -> total > 0 && matches == total;
                case NONE -> matches == 0;
            };
        }
        if (np.inner() instanceof FieldPattern) {
            if (node.fields == null) return q == Quantifier.NONE;
            int total = node.fields.size();
            long matches = node.fields.stream().filter(f -> matchesField(f, node, inner)).count();
            return switch (q) {
                case ANY -> matches > 0;
                case ALL -> total > 0 && matches == total;
                case NONE -> matches == 0;
            };
        }
        return false;
    }

    private boolean matchesMethod(MethodNode m, ClassNode owner, List<Predicate> preds) {
        for (Predicate p : preds) if (!evalMethodPredicate(m, owner, p)) return false;
        return true;
    }

    private boolean evalMethodPredicate(MethodNode m, ClassNode owner, Predicate p) {
        return switch (p) {
            case NamePredicate np -> np.spec().matches(m.name);
            case AccessPredicate ap -> hasMethodAccess(m.access, ap.flag());
            case DescPredicate dp -> dp.spec().matches(m.desc);
            case AnnotationPredicate ap -> hasAnnotation(m.visibleAnnotations, ap.spec())
                    || hasAnnotation(m.invisibleAnnotations, ap.spec());
            case InstructionCountPredicate icp -> icp.op().apply(
                    m.instructions == null ? 0 : m.instructions.size(), icp.value());
            case ContainsPredicate cp -> containsInstruction(m, cp.matcher());
            case OrPredicate op -> op.alternatives().stream().anyMatch(a -> evalMethodPredicate(m, owner, a));
            case NotPredicate np -> !evalMethodPredicate(m, owner, np.inner());
            case NestedPattern np -> false;
            case ExtendsPredicate ep -> false;
            case ImplementsPredicate ip -> false;
            case MethodCountPredicate mcp -> false;
            case FieldCountPredicate fcp -> false;
        };
    }

    private boolean matchesField(FieldNode f, ClassNode owner, List<Predicate> preds) {
        for (Predicate p : preds) if (!evalFieldPredicate(f, owner, p)) return false;
        return true;
    }

    private boolean evalFieldPredicate(FieldNode f, ClassNode owner, Predicate p) {
        return switch (p) {
            case NamePredicate np -> np.spec().matches(f.name);
            case AccessPredicate ap -> hasFieldAccess(f.access, ap.flag());
            case DescPredicate dp -> dp.spec().matches(f.desc);
            case AnnotationPredicate ap -> hasAnnotation(f.visibleAnnotations, ap.spec())
                    || hasAnnotation(f.invisibleAnnotations, ap.spec());
            case OrPredicate op -> op.alternatives().stream().anyMatch(a -> evalFieldPredicate(f, owner, a));
            case NotPredicate np -> !evalFieldPredicate(f, owner, np.inner());
            default -> false;
        };
    }

    private boolean containsInstruction(MethodNode m, InstructionMatcher matcher) {
        if (m.instructions == null) return false;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (instructionMatches(insn, matcher)) return true;
        }
        return false;
    }

    private boolean instructionMatches(AbstractInsnNode insn, InstructionMatcher matcher) {
        return switch (matcher) {
            case InstructionMatcher.Ldc ldc -> {
                if (!(insn instanceof LdcInsnNode l)) yield false;
                yield ldc.value().matches(String.valueOf(l.cst));
            }
            case InstructionMatcher.Invoke inv -> {
                if (!(insn instanceof MethodInsnNode mi)) yield false;
                boolean ownerOk = inv.owner().matches(mi.owner)
                        || inv.owner().matches(mi.owner.replace('/', '.'));
                boolean nameOk = inv.name().matches(mi.name);
                boolean descOk = inv.desc().matches(mi.desc);
                yield ownerOk && nameOk && descOk;
            }
            case InstructionMatcher.FieldAccess fa -> {
                if (!(insn instanceof FieldInsnNode fi)) yield false;
                int wantedOpcode = switch (fa.op()) {
                    case GETFIELD -> Opcodes.GETFIELD;
                    case PUTFIELD -> Opcodes.PUTFIELD;
                    case GETSTATIC -> Opcodes.GETSTATIC;
                    case PUTSTATIC -> Opcodes.PUTSTATIC;
                };
                if (fi.getOpcode() != wantedOpcode) yield false;
                boolean ownerOk = fa.owner().matches(fi.owner)
                        || fa.owner().matches(fi.owner.replace('/', '.'));
                boolean nameOk = fa.name().matches(fi.name);
                yield ownerOk && nameOk;
            }
            case InstructionMatcher.NewInstance ni -> {
                if (!(insn instanceof TypeInsnNode tn)) yield false;
                if (tn.getOpcode() != Opcodes.NEW) yield false;
                yield ni.owner().matches(tn.desc) || ni.owner().matches(tn.desc.replace('/', '.'));
            }
            case InstructionMatcher.Opcode op -> opcodeNameFor(insn.getOpcode()).equalsIgnoreCase(op.name());
        };
    }

    private static ClassNode readClassNode(ClassEntry entry) {
        ClassNode node = new ClassNode();
        ClassReader reader = new ClassReader(entry.bytes());
        reader.accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return node;
    }

    private static boolean hasAnnotation(List<AnnotationNode> list, MatchSpec spec) {
        if (list == null) return false;
        for (AnnotationNode a : list) {
            if (spec.matches(a.desc)) return true;
        }
        return false;
    }

    private static boolean hasClassAccess(int access, String flag) {
        return switch (flag) {
            case "public" -> (access & Opcodes.ACC_PUBLIC) != 0;
            case "private" -> (access & Opcodes.ACC_PRIVATE) != 0;
            case "protected" -> (access & Opcodes.ACC_PROTECTED) != 0;
            case "final" -> (access & Opcodes.ACC_FINAL) != 0;
            case "abstract" -> (access & Opcodes.ACC_ABSTRACT) != 0;
            case "interface" -> (access & Opcodes.ACC_INTERFACE) != 0;
            case "annotation" -> (access & Opcodes.ACC_ANNOTATION) != 0;
            case "enum" -> (access & Opcodes.ACC_ENUM) != 0;
            case "record" -> (access & Opcodes.ACC_RECORD) != 0;
            case "synthetic" -> (access & Opcodes.ACC_SYNTHETIC) != 0;
            case "super" -> (access & Opcodes.ACC_SUPER) != 0;
            case "module" -> (access & Opcodes.ACC_MODULE) != 0;
            default -> false;
        };
    }

    private static boolean hasMethodAccess(int access, String flag) {
        return switch (flag) {
            case "public" -> (access & Opcodes.ACC_PUBLIC) != 0;
            case "private" -> (access & Opcodes.ACC_PRIVATE) != 0;
            case "protected" -> (access & Opcodes.ACC_PROTECTED) != 0;
            case "static" -> (access & Opcodes.ACC_STATIC) != 0;
            case "final" -> (access & Opcodes.ACC_FINAL) != 0;
            case "synchronized" -> (access & Opcodes.ACC_SYNCHRONIZED) != 0;
            case "abstract" -> (access & Opcodes.ACC_ABSTRACT) != 0;
            case "native" -> (access & Opcodes.ACC_NATIVE) != 0;
            case "bridge" -> (access & Opcodes.ACC_BRIDGE) != 0;
            case "varargs" -> (access & Opcodes.ACC_VARARGS) != 0;
            case "strict" -> (access & Opcodes.ACC_STRICT) != 0;
            case "synthetic" -> (access & Opcodes.ACC_SYNTHETIC) != 0;
            default -> false;
        };
    }

    private static boolean hasFieldAccess(int access, String flag) {
        return switch (flag) {
            case "public" -> (access & Opcodes.ACC_PUBLIC) != 0;
            case "private" -> (access & Opcodes.ACC_PRIVATE) != 0;
            case "protected" -> (access & Opcodes.ACC_PROTECTED) != 0;
            case "static" -> (access & Opcodes.ACC_STATIC) != 0;
            case "final" -> (access & Opcodes.ACC_FINAL) != 0;
            case "volatile" -> (access & Opcodes.ACC_VOLATILE) != 0;
            case "transient" -> (access & Opcodes.ACC_TRANSIENT) != 0;
            case "synthetic" -> (access & Opcodes.ACC_SYNTHETIC) != 0;
            case "enum" -> (access & Opcodes.ACC_ENUM) != 0;
            default -> false;
        };
    }

    private static String opcodeNameFor(int opcode) {
        if (opcode < 0) return "";
        return switch (opcode) {
            case Opcodes.NOP -> "nop";
            case Opcodes.ACONST_NULL -> "aconst_null";
            case Opcodes.IRETURN -> "ireturn";
            case Opcodes.LRETURN -> "lreturn";
            case Opcodes.FRETURN -> "freturn";
            case Opcodes.DRETURN -> "dreturn";
            case Opcodes.ARETURN -> "areturn";
            case Opcodes.RETURN -> "return";
            case Opcodes.ATHROW -> "athrow";
            case Opcodes.MONITORENTER -> "monitorenter";
            case Opcodes.MONITOREXIT -> "monitorexit";
            case Opcodes.ARRAYLENGTH -> "arraylength";
            case Opcodes.CHECKCAST -> "checkcast";
            case Opcodes.INSTANCEOF -> "instanceof";
            case Opcodes.TABLESWITCH -> "tableswitch";
            case Opcodes.LOOKUPSWITCH -> "lookupswitch";
            case Opcodes.NEW -> "new";
            case Opcodes.NEWARRAY -> "newarray";
            case Opcodes.ANEWARRAY -> "anewarray";
            case Opcodes.INVOKEVIRTUAL -> "invokevirtual";
            case Opcodes.INVOKESPECIAL -> "invokespecial";
            case Opcodes.INVOKESTATIC -> "invokestatic";
            case Opcodes.INVOKEINTERFACE -> "invokeinterface";
            case Opcodes.INVOKEDYNAMIC -> "invokedynamic";
            case Opcodes.IFEQ -> "ifeq";
            case Opcodes.IFNE -> "ifne";
            case Opcodes.GOTO -> "goto";
            case Opcodes.IFNULL -> "ifnull";
            case Opcodes.IFNONNULL -> "ifnonnull";
            default -> "opcode_" + opcode;
        };
    }
}
