package main.visitor.type;

import main.ast.nodes.Node;
import main.ast.nodes.Program;
import main.ast.nodes.declaration.*;
import main.ast.nodes.declaration.struct.*;
import main.ast.nodes.expression.Expression;
import main.ast.nodes.expression.Identifier;
import main.ast.nodes.expression.ListAccessByIndex;
import main.ast.nodes.expression.StructAccess;
import main.ast.nodes.expression.operators.BinaryOperator;
import main.ast.nodes.statement.*;
import main.ast.types.*;
import main.ast.types.primitives.BoolType;
import main.ast.types.primitives.IntType;
import main.ast.types.primitives.VoidType;
import main.compileError.typeError.*;
import main.symbolTable.SymbolTable;
import main.symbolTable.exceptions.ItemAlreadyExistsException;
import main.symbolTable.exceptions.ItemNotFoundException;
import main.symbolTable.items.StructSymbolTableItem;
import main.symbolTable.items.VariableSymbolTableItem;
import main.symbolTable.utils.Stack;
import main.visitor.Visitor;

import java.util.ArrayList;


class Scope {
    boolean returnExists = false;
}

public class TypeChecker extends Visitor<Void> {
    ExpressionTypeChecker expressionTypeChecker;
    Scope top;
    Stack<Scope> scopes;
    Identifier returnIdentifier;
    boolean declarationPermitted;

    public TypeChecker() {
        top = new Scope();
        scopes = new Stack<>();
        expressionTypeChecker = new ExpressionTypeChecker();
        returnIdentifier = new Identifier("RETURN");
        declarationPermitted = false;
    }

    @Override
    public Void visit(Program program) {
        for (StructDeclaration struct : program.getStructs()) {
            struct.accept(this);
        }
        for (FunctionDeclaration function : program.getFunctions()) {
            function.accept(this);
        }
        program.getMain().accept(this);
        return null;
    }

    @Override
    public Void visit(FunctionDeclaration functionDec) {
        pushScope(SymbolTable.root);
        recursiveTypeCheck(functionDec.getReturnType(), functionDec);
        var returnItem = new VariableSymbolTableItem(returnIdentifier);
        returnItem.setType(functionDec.getReturnType());
        try {
            SymbolTable.top.put(returnItem);
        } catch (ItemAlreadyExistsException ignore) {
        }
        for (VariableDeclaration arg : functionDec.getArgs()) {
            arg.accept(this);
        }
        functionDec.getBody().accept(this);
        if (!(top.returnExists || functionDec.getReturnType() instanceof VoidType)) {
            functionDec.addError(new MissingReturnStatement(functionDec.getLine(), functionDec.getFunctionName().getName()));
        }
        popScope();
        return null;
    }

    @Override
    public Void visit(MainDeclaration mainDec) {
        SymbolTable.push(new SymbolTable(SymbolTable.root));
        mainDec.getBody().accept(this);
        SymbolTable.pop();
        return null;
    }

    @Override
    public Void visit(VariableDeclaration variableDec) {
        VariableSymbolTableItem variableSymbolTableItem = new VariableSymbolTableItem(variableDec.getVarName());
        recursiveTypeCheck(variableDec.getVarType(), variableDec);
        variableSymbolTableItem.setType(variableDec.getVarType());
        try {
            SymbolTable.top.put(variableSymbolTableItem);
        } catch (ItemAlreadyExistsException ignore) {
        }
        return null;
    }

    @Override
    public Void visit(StructDeclaration structDec) {
        SymbolTable.push(new SymbolTable(SymbolTable.root));
        structDec.getBody().accept(this);
        try {
            var structItem = SymbolTable.root.getItem(StructSymbolTableItem.START_KEY + structDec.getStructName().getName());
            ((StructSymbolTableItem) structItem).setStructSymbolTable(SymbolTable.top);
        } catch (ItemNotFoundException ignore) {
        }
        SymbolTable.pop();
        return null;
    }

    @Override
    public Void visit(SetGetVarDeclaration setGetVarDec) {
        declarationPermitted = true;
        recursiveTypeCheck(setGetVarDec.getVarType(), setGetVarDec);
        SymbolTable.push(new SymbolTable(SymbolTable.top));
        var item = new VariableSymbolTableItem(setGetVarDec.getVarName());
        item.setType(setGetVarDec.getVarType());
        try {
            SymbolTable.top.put(item);
        } catch (ItemAlreadyExistsException ignore) {
        }
        for (VariableDeclaration arg : setGetVarDec.getArgs()) {
            arg.accept(this);
        }
        setGetVarDec.getSetterBody().accept(this);
        SymbolTable.pop();
        SymbolTable.push(new SymbolTable(SymbolTable.top));
        var returnItem = new VariableSymbolTableItem(returnIdentifier);
        returnItem.setType(setGetVarDec.getVarType());
        try {
            SymbolTable.top.put(returnItem);
        } catch (ItemAlreadyExistsException ignore) {
        }
        setGetVarDec.getGetterBody().accept(this);
        SymbolTable.pop();
        ArrayList<Type> args = new ArrayList<>();
        for (VariableDeclaration varDec : setGetVarDec.getArgs()) {
            args.add(varDec.getVarType());
        }
        item.setType(new FptrType(args, setGetVarDec.getVarType()));
        try {
            SymbolTable.top.put(item);
        } catch (ItemAlreadyExistsException ignore) {
        }
        declarationPermitted = false;
        return null;
    }

    @Override
    public Void visit(AssignmentStmt assignmentStmt) {
        var exp = assignmentStmt.getLValue();
        if (!(exp instanceof StructAccess || exp instanceof Identifier || exp instanceof ListAccessByIndex)) {
            assignmentStmt.addError(new LeftSideNotLvalue(exp.getLine()));
        }
        var leftType = assignmentStmt.getLValue().accept(expressionTypeChecker);
        var rightType = assignmentStmt.getRValue().accept(expressionTypeChecker);
        if (!recursiveCompare(leftType, rightType)) {
            assignmentStmt.addError(new UnsupportedOperandType(assignmentStmt.getLine(), BinaryOperator.assign.toString()));
        }
        return null;
    }

    @Override
    public Void visit(BlockStmt blockStmt) {
        for (Statement statement : blockStmt.getStatements()) {
            statement.accept(this);
        }
        return null;
    }

    @Override
    public Void visit(ConditionalStmt conditionalStmt) {
        var conditionType = conditionalStmt.getCondition().accept(expressionTypeChecker);
        if (!recursiveCompare(conditionType, new BoolType())) {
            conditionalStmt.addError(new ConditionNotBool(conditionalStmt.getCondition().getLine()));
        }
        pushScope(SymbolTable.top);
        conditionalStmt.getThenBody().accept(this);
        var returnExists = top.returnExists;
        popScope();
        if (conditionalStmt.getElseBody() != null) {
            pushScope(SymbolTable.top);
            conditionalStmt.getElseBody().accept(this);
            returnExists = top.returnExists && returnExists;
            popScope();
        }
        top.returnExists = returnExists && conditionalStmt.getElseBody() != null || top.returnExists;
        return null;
    }

    @Override
    public Void visit(FunctionCallStmt functionCallStmt) {
        expressionTypeChecker.is_stmt = true;
        functionCallStmt.getFunctionCall().accept(expressionTypeChecker);
        return null;
    }

    @Override
    public Void visit(DisplayStmt displayStmt) {
        var type = value(displayStmt.getArg());
        if (!(type instanceof BoolType || type instanceof IntType || type instanceof ListType || type instanceof NoType)) {
            displayStmt.addError(new UnsupportedTypeForDisplay(displayStmt.getLine()));
        }
        return null;
    }

    private Type value(Expression expression) {
        var type = expression.accept(expressionTypeChecker);
        if (type instanceof VoidType) {
            return new NoType();
        }
        return type;
    }

    @Override
    public Void visit(ReturnStmt returnStmt) {
        top.returnExists = true;
        VariableSymbolTableItem variableSymbolTableItem;
        try {
            variableSymbolTableItem = (VariableSymbolTableItem) SymbolTable.top.getItem(VariableSymbolTableItem.START_KEY + returnIdentifier.getName());
        } catch (ItemNotFoundException ignore) {
            returnStmt.addError(new CannotUseReturn(returnStmt.getLine()));
            return null;
        }
        if (!(variableSymbolTableItem.getType() instanceof VoidType) && returnStmt.getReturnedExpr() == null) {
            returnStmt.addError(new ReturnValueNotMatchFunctionReturnType(returnStmt.getLine()));
        } else {
            var retType = returnStmt.getReturnedExpr() == null ? new VoidType() : returnStmt.getReturnedExpr().accept(expressionTypeChecker);
            if (!recursiveCompare(retType, variableSymbolTableItem.getType())) {
                returnStmt.addError(new ReturnValueNotMatchFunctionReturnType(returnStmt.getLine()));
            }
        }
        return null;
    }

    @Override
    public Void visit(LoopStmt loopStmt) {
        var conditionType = loopStmt.getCondition().accept(expressionTypeChecker);
        if (!recursiveCompare(conditionType, new BoolType())) {
            loopStmt.addError(new ConditionNotBool(loopStmt.getCondition().getLine()));
        }
        pushScope(SymbolTable.top);
        loopStmt.getBody().accept(this);
        popScope();
        return null;
    }

    @Override
    public Void visit(VarDecStmt varDecStmt) {
        if (declarationPermitted) {
            varDecStmt.addError(new CannotUseDefineVar(varDecStmt.getLine()));
        }
        for (VariableDeclaration var : varDecStmt.getVars()) {
            var variableSymbolTableItem = new VariableSymbolTableItem(var.getVarName());
            recursiveTypeCheck(var.getVarType(), varDecStmt);
            variableSymbolTableItem.setType(var.getVarType());
            try {
                SymbolTable.top.put(variableSymbolTableItem);
            } catch (ItemAlreadyExistsException ignore) {
            }
            if (var.getDefaultValue() != null) {
                var type = value(var.getDefaultValue());
                if (!recursiveCompare(type, var.getVarType())) {
                    var.addError(new UnsupportedOperandType(var.getLine(), BinaryOperator.assign.toString()));
                }
            }
        }
        return null;
    }

    @Override
    public Void visit(ListAppendStmt listAppendStmt) {
        listAppendStmt.getListAppendExpr().accept(expressionTypeChecker);
        return null;
    }

    @Override
    public Void visit(ListSizeStmt listSizeStmt) {
        listSizeStmt.getListSizeExpr().accept(expressionTypeChecker);
        return null;
    }

    private void pushScope(SymbolTable pre) {
        SymbolTable.push(new SymbolTable(pre));
        top = new Scope();
        scopes.push(top);
    }

    private void popScope() {
        SymbolTable.pop();
        top = scopes.pop();
    }

    private void recursiveTypeCheck(Type a, Node b) {
        if (a instanceof StructType) {
            recursiveTypeCheck((StructType) a, b);
        }
        if (a instanceof FptrType) {
            recursiveTypeCheck((FptrType) a, b);
        }
        if (a instanceof ListType) {
            recursiveTypeCheck((ListType) a, b);
        }
    }

    private void recursiveTypeCheck(StructType a, Node b) {
        try {
            SymbolTable.root.getItem(StructSymbolTableItem.START_KEY + a.getStructName().getName());
        } catch (ItemNotFoundException e) {
            b.addError(new StructNotDeclared(b.getLine(), a.getStructName().getName()));
        }
    }

    private void recursiveTypeCheck(FptrType a, Node b) {
        for (Type t : a.getArgsType()) {
            recursiveTypeCheck(t, b);
        }
        recursiveTypeCheck(a.getReturnType(), b);
    }

    private void recursiveTypeCheck(ListType a, Node b) {
        recursiveTypeCheck(a.getType(), b);
    }

    private boolean recursiveCompare(Type a, Type b) {
        if (a instanceof NoType || b instanceof NoType)
            return true;
        if (!a.getClass().equals(b.getClass()))
            return false;
        if (a instanceof StructType) {
            return recursiveCompare((StructType) a, (StructType) b);
        }
        if (a instanceof ListType) {
            return recursiveCompare((ListType) a, (ListType) b);
        }
        if (a instanceof FptrType) {
            return recursiveCompare((FptrType) a, (FptrType) b);
        }
        return true;
    }

    private boolean recursiveCompare(StructType a, StructType b) {
        return a.getStructName().getName().equals(b.getStructName().getName());
    }

    private boolean recursiveCompare(FptrType a, FptrType b) {
        if (a.getArgsType().size() != b.getArgsType().size())
            return false;
        if (!recursiveCompare(a.getReturnType(), b.getReturnType()))
            return false;
        for (int i = 0; i < a.getArgsType().size(); i++) {
            if (!recursiveCompare(a.getArgsType().get(i), b.getArgsType().get(i)))
                return false;
        }
        return true;
    }

    private boolean recursiveCompare(ListType type1, ListType type2) {
        return recursiveCompare(type1.getType(), type2.getType());
    }
}
