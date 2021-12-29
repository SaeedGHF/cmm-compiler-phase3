package main.visitor.type;

import main.ast.nodes.expression.*;
import main.ast.nodes.expression.operators.BinaryOperator;
import main.ast.nodes.expression.operators.UnaryOperator;
import main.ast.nodes.expression.values.primitive.BoolValue;
import main.ast.nodes.expression.values.primitive.IntValue;
import main.ast.types.*;
import main.ast.types.primitives.BoolType;
import main.ast.types.primitives.IntType;
import main.ast.types.primitives.VoidType;
import main.compileError.typeError.*;
import main.symbolTable.SymbolTable;
import main.symbolTable.exceptions.ItemNotFoundException;
import main.symbolTable.items.FunctionSymbolTableItem;
import main.symbolTable.items.StructSymbolTableItem;
import main.symbolTable.items.SymbolTableItem;
import main.symbolTable.items.VariableSymbolTableItem;
import main.visitor.Visitor;

import java.util.ArrayList;

public class ExpressionTypeChecker extends Visitor<Type> {
    public boolean is_stmt = false;

    @Override
    public Type visit(BinaryExpression binaryExpression) {
        Type leftType = binaryExpression.getFirstOperand().accept(this);
        Type rightType = binaryExpression.getSecondOperand().accept(this);
        if (leftType instanceof VoidType) {
            binaryExpression.addError(new CantUseValueOfVoidFunction(binaryExpression.getLine()));
        }
        if (rightType instanceof VoidType) {
            binaryExpression.addError(new CantUseValueOfVoidFunction(binaryExpression.getLine()));
            if (leftType instanceof VoidType)
                return new NoType();
        }
        BinaryOperator operator = binaryExpression.getBinaryOperator();
        if (operator == BinaryOperator.and || operator == BinaryOperator.or) {
            if ((leftType instanceof BoolType && rightType instanceof BoolType)) {
                return new BoolType();
            } else if (!(leftType instanceof NoType && rightType instanceof BoolType) &&
                    !(leftType instanceof BoolType && rightType instanceof NoType) &&
                    !(leftType instanceof NoType && rightType instanceof NoType)) {
                binaryExpression.addError(new UnsupportedOperandType(binaryExpression.getLine(), operator.name()));
            }
            return new NoType();
        }
        if (operator == BinaryOperator.assign) {
            if ((leftType instanceof IntType && rightType instanceof IntType) ||
                    (leftType instanceof BoolType && rightType instanceof BoolType) ||
                    (leftType instanceof StructType && rightType instanceof StructType) ||
                    (leftType instanceof FptrType && rightType instanceof FptrType && compareFunctionPointer((FptrType) leftType, (FptrType) rightType)) ||
                    (leftType instanceof ListType && rightType instanceof ListType && compareListType((ListType) leftType, (ListType) rightType))) {
                return rightType;
            } else if (!(leftType instanceof NoType) && !(rightType instanceof NoType)) {
                binaryExpression.addError(new UnsupportedOperandType(binaryExpression.getLine(), operator.name()));
            }
            return new NoType();
        }
        if (operator == BinaryOperator.eq) {
            if (leftType instanceof ListType || rightType instanceof ListType) {
                binaryExpression.addError(new UnsupportedOperandType(binaryExpression.getLine(), operator.name()));
            } else if ((leftType instanceof IntType && rightType instanceof IntType) ||
                    (leftType instanceof BoolType && rightType instanceof BoolType) ||
                    (leftType instanceof StructType && rightType instanceof StructType) ||
                    (leftType instanceof FptrType && rightType instanceof FptrType)) {
                return new BoolType();
            } else if (!(leftType instanceof NoType) && !(rightType instanceof NoType)) {
                binaryExpression.addError(new UnsupportedOperandType(binaryExpression.getLine(), operator.name()));
            }
            return new NoType();
        }
        if ((leftType instanceof IntType && rightType instanceof IntType)) {
            if (operator == BinaryOperator.lt || operator == BinaryOperator.eq ||
                    operator == BinaryOperator.gt)
                return new BoolType();
            return new IntType();
        } else if (!(leftType instanceof NoType && rightType instanceof IntType) &&
                !(leftType instanceof IntType && rightType instanceof NoType) &&
                !(leftType instanceof NoType && rightType instanceof NoType)) {
            binaryExpression.addError(new UnsupportedOperandType(binaryExpression.getLine(), operator.name()));
        }
        return new NoType();
    }

    @Override
    public Type visit(UnaryExpression unaryExpression) {
        Type exp = unaryExpression.getOperand().accept(this);
        if (unaryExpression.getOperator() == UnaryOperator.not) {
            if (exp instanceof BoolType) {
                return new BoolType();
            } else if (exp instanceof IntType) {
                unaryExpression.addError(new UnsupportedOperandType(unaryExpression.getLine(), "not"));
            }
        } else {
            if (exp instanceof IntType) {
                return new IntType();
            } else if (exp instanceof BoolType) {
                unaryExpression.addError(new UnsupportedOperandType(unaryExpression.getLine(), "minus"));
            }
        }
        return new NoType();
    }

    @Override
    public Type visit(FunctionCall funcCall) {
        Type instance = funcCall.getInstance().accept(this);
        if (!(instance instanceof FptrType)) {
            funcCall.addError(new CallOnNoneFptrType(funcCall.getLine()));
            return new NoType();
        }
        ArrayList<Type> args = new ArrayList<>();
        for (Expression arg : funcCall.getArgs()) {
            Type item = arg.accept(this);
            args.add(item);
        }
        if (args.size() != ((FptrType) instance).getArgsType().size() ||
                !compareTypeArrayList(((FptrType) instance).getArgsType(), args)) {
            funcCall.addError(new ArgsInFunctionCallNotMatchDefinition(funcCall.getLine()));
            //return ((FptrType) instance).getReturnType();
        }
        if (((FptrType) instance).getReturnType() instanceof VoidType && !is_stmt) {
            funcCall.addError(new CantUseValueOfVoidFunction(funcCall.getLine()));
        }
        is_stmt = false;
        return ((FptrType) instance).getReturnType();
    }

    @Override
    public Type visit(Identifier identifier) {
        try {
            SymbolTableItem item = SymbolTable.top.getItem(VariableSymbolTableItem.START_KEY + identifier.getName());
            Type id = ((VariableSymbolTableItem) item).getType();
            if (id instanceof StructType) {
                Identifier structName = ((StructType) id).getStructName();
                try {
                    SymbolTable.top.getItem(StructSymbolTableItem.START_KEY + structName.getName());
                    return id;
                } catch (ItemNotFoundException ex) {
                    return new NoType();
                }
            } else {
                return id;
            }

        } catch (ItemNotFoundException ex) {
            try {
                FunctionSymbolTableItem item = (FunctionSymbolTableItem) SymbolTable.top.getItem(FunctionSymbolTableItem.START_KEY + identifier.getName());
                return new FptrType(item.getArgTypes(), item.getReturnType());
            } catch (ItemNotFoundException ex2) {
                identifier.addError(new VarNotDeclared(identifier.getLine(), identifier.getName()));
            }
        }
        return new NoType();
    }

    @Override
    public Type visit(ListAccessByIndex listAccessByIndex) {
        Type instance = listAccessByIndex.getInstance().accept(this);
        Type index = listAccessByIndex.getIndex().accept(this);
        if (instance instanceof ListType && index instanceof IntType) {
            return ((ListType) instance).getType();
        } else if (instance instanceof ListType && !(index instanceof NoType)) {
            listAccessByIndex.addError(new ListIndexNotInt(listAccessByIndex.getLine()));
        } else if (!(instance instanceof NoType) && index instanceof IntType) {
            listAccessByIndex.addError(new AccessByIndexOnNonList(listAccessByIndex.getLine()));
        }
        return new NoType();
    }

    @Override
    public Type visit(StructAccess structAccess) {
        Type instance = structAccess.getInstance().accept(this);
        if (instance instanceof NoType) {
            return new NoType();
        }
        if (!(instance instanceof StructType)) {
            structAccess.addError(new AccessOnNonStruct(structAccess.getLine()));
            return new NoType();
        }
        String varName = structAccess.getElement().getName();
        String structName = ((StructType) instance).getStructName().getName();
        try {
            StructSymbolTableItem struct = (StructSymbolTableItem) SymbolTable.root.getItem(StructSymbolTableItem.START_KEY + structName);
            SymbolTable structTable = struct.getStructSymbolTable();
            try {
                VariableSymbolTableItem element = (VariableSymbolTableItem) structTable.getItem(VariableSymbolTableItem.START_KEY + varName);
                return element.getType();
            } catch (ItemNotFoundException ex) {
                structAccess.addError(new StructMemberNotFound(structAccess.getLine(), structName, varName));
                return new NoType();
            }
        } catch (ItemNotFoundException ex) {
            return new NoType();
        }
    }

    @Override
    public Type visit(ListSize listSize) {
        Type list = listSize.getArg().accept(this);
        if (list instanceof ListType) {
            return new IntType();
        }
        if (!(list instanceof NoType)) {
            listSize.addError(new GetSizeOfNonList(listSize.getLine()));
        }
        return null;
    }

    @Override
    public Type visit(ListAppend listAppend) {
        Type listType = listAppend.getListArg().accept(this);
        if (!(listType instanceof ListType)) {
            listAppend.addError(new AppendToNonList(listAppend.getLine()));
            return new NoType();
        }
        Type listElement = listAppend.getElementArg().accept(this);
        Type listArg = ((ListType) listType).getType();
        if ((listElement instanceof BoolType && listArg instanceof BoolType) ||
                (listElement instanceof IntType && listArg instanceof IntType) ||
                (listElement instanceof StructType && listArg instanceof StructType) ||
                (listElement instanceof ListType && listArg instanceof ListType)) {
            return new VoidType();
        }
        if (!(listArg instanceof NoType)) {
            listAppend.addError(new NewElementTypeNotMatchListType(listAppend.getLine()));
        }
        return new NoType();
    }

    @Override
    public Type visit(ExprInPar exprInPar) {
        return exprInPar.getInputs().get(0).accept(this);
    }

    @Override
    public Type visit(IntValue intValue) {
        return new IntType();
    }

    @Override
    public Type visit(BoolValue boolValue) {
        return new BoolType();
    }

    private boolean compareFunctionPointer(FptrType a, FptrType b) {
        if (a.getReturnType() instanceof IntType && !(b.getReturnType() instanceof IntType))
            return false;
        if (a.getReturnType() instanceof BoolType && !(b.getReturnType() instanceof BoolType))
            return false;
        if (a.getReturnType() instanceof StructType && !(b.getReturnType() instanceof StructType))
            return false;
        if (a.getReturnType() instanceof ListType && !(b.getReturnType() instanceof ListType))
            return false;
        if (a.getReturnType() instanceof VoidType && !(b.getReturnType() instanceof VoidType))
            return false;
        if (a.getReturnType() instanceof FptrType && !(b.getReturnType() instanceof FptrType))
            return false;
        if (a.getArgsType().size() != b.getArgsType().size())
            return false;
        return compareTypeArrayList(a.getArgsType(), b.getArgsType());
    }

    private boolean compareListType(ListType a, ListType b) {
        if (a.getType() instanceof IntType && b.getType() instanceof IntType)
            return true;
        if (a.getType() instanceof BoolType && b.getType() instanceof BoolType)
            return true;
        if (a.getType() instanceof StructType && b.getType() instanceof StructType)
            return true;
        return a.getType() instanceof ListType && b.getType() instanceof ListType;
    }

    private boolean compareTypeArrayList(ArrayList<Type> a, ArrayList<Type> b) {
        for (int i = 0; i < a.size(); i++) {
            if ((b.get(i) instanceof BoolType && a.get(i) instanceof BoolType) ||
                    (b.get(i) instanceof IntType && a.get(i) instanceof IntType) ||
                    (b.get(i) instanceof StructType && a.get(i) instanceof StructType) ||
                    (b.get(i) instanceof ListType && a.get(i) instanceof ListType) ||
                    (b.get(i) instanceof FptrType && a.get(i) instanceof FptrType)) {
            } else {
                return false;
            }
        }
        return true;
    }
}