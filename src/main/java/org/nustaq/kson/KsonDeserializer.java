package org.nustaq.kson;


import org.nustaq.serialization.FSTClazzInfo;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.*;

/**
 * Copyright (c) 2012, Ruediger Moeller. All rights reserved.
 * <p/>
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * <p/>
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * <p/>
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301  USA
 * <p/>
 * Date: 20.12.13
 * Time: 01:16
 */

/**
 * parses kson format as well as json. somewhat quick&dirty, anyway targeted for easy mapping of config files/data and
 * to connect kontraktor's actors to slow-end tech like webservices & jscript front ends.
 *
 * Note: this is pretty fuzzy code (typeguessing, best effort parsing ..)
 */
public class KsonDeserializer {

    public static boolean DEBUG_STACK = false; // turn on for parsestack on error, slows down !!

    protected KsonCharInput in;
    protected KsonTypeMapper mapper;
    protected Stack<ParseStep> stack;

    private KsonArgTypesResolver argTypesRessolver;

    static class ParseStep {
        KsonCharInput in;
        int position;
        String action;

        ParseStep(KsonCharInput in, int position, String action) {
            this.in = in;
            this.position = position;
            this.action = action;
        }

        ParseStep(String action, KsonCharInput in) {
            this(in, in.position(),action);
        }

        @Override
        public String toString() {
            return ""+action+" at pos:"+position;
        }
    }


    public KsonDeserializer(KsonCharInput in, KsonTypeMapper mapper) {
        this.in = in;
        this.mapper = mapper;
        if (DEBUG_STACK) {
            stack = new Stack<>();
            if ( in instanceof KsonStringCharInput ) {
                ((KsonStringCharInput) in).stack = stack;
            }
        }
    }

    public void skipWS() {
        int ch = in.readChar();
        while (ch >= 0 && Character.isWhitespace(ch)) {
            ch = in.readChar();
        }
        if (ch == '#') {
            ch = in.readChar();
            while (ch >= 0 && ch != '\n') {
                ch = in.readChar();
            }
            skipWS();
        } else if (ch > 0)
            in.back(1);
    }

    public Object readObject(Class expect, Class genericKeyType, Class genericValueType) throws Exception {
        try {
            skipWS();
            if (in.isEof())
                return null;
            String type = readId();
            Object literal = mapper.mapLiteral(type);
            if (literal != null ) {
                return literal == mapper.NULL_LITERAL ? null:literal;
            }
            skipWS();
            Class mappedClass = null;
            if ("".equals(type)) {
                mappedClass = expect;
            } else {
                mappedClass = mapper.getType(type);
            }
            if (mappedClass == null) {
                if ("".equals(type)) {
                    // always with json
                    // => take first attribute as type (see subclass)
                    final int position = in.position();
                    String clz = huntType();
                    if (clz != null)
                        mappedClass = mapper.getType(clz);
                    // rewind
                    in.back(in.position() - position);
                } else
                    return type; // assume string
            }
            if (mappedClass == List.class || mappedClass == Collection.class)
                mappedClass = ArrayList.class;
            if (mappedClass == Map.class)
                mappedClass = HashMap.class;
            FSTClazzInfo clInfo = Kson.conf.getCLInfoRegistry().getCLInfo(mappedClass);
            if (DEBUG_STACK) {
                if ( clInfo != null ) {
                    stack.push(new ParseStep("try reading type " + clInfo.getClazz().getName(), in));
                } else
                    stack.push(new ParseStep("try reading unknown object type", in));
            }
            int ch = in.readChar();
            if (ch != '{' && ch != '[') {
                throw new KsonParseException("expected '{' or '['", in);
            }
            Object res = null;
            if (Map.class.isAssignableFrom(clInfo.getClazz())) {
                res = clInfo.newInstance(true);
                if (DEBUG_STACK) {
                    stack.push(new ParseStep("read map " + clInfo.getClazz().getName()+"<"+genericKeyType+","+genericValueType+">", in));
                }
                List keyVals = readList(genericKeyType, genericValueType);
                for (int i = 0; i < keyVals.size(); i += 2) {
                    Object fi = keyVals.get(i);
                    Object val = keyVals.get(i + 1);
                    ((Map) res).put(fi, val);
                }
                if (DEBUG_STACK) {
                    stack.pop();
                }
            } else if (Collection.class.isAssignableFrom(clInfo.getClazz())) {
                List keyVals = readList(genericKeyType, genericKeyType);
                if (clInfo.getClazz() == ArrayList.class) { // default constructor fails ...
                    res = new ArrayList<>(keyVals.size());
                } else if (clInfo.getClazz() == HashSet.class) { // default constructor fails ...
                    res = new HashSet<>(keyVals.size());
                } else {
                    res = clInfo.newInstance(true);
                }
                if (DEBUG_STACK) {
                    stack.push(new ParseStep("read list " + clInfo.getClazz().getName()+"<"+genericKeyType+"|"+genericValueType+">", in));
                }
                for (int i = 0; i < keyVals.size(); i++) {
                    Object o = keyVals.get(i);
                    ((Collection) res).add(o);
                }
                if (DEBUG_STACK) {
                    stack.pop();
                }
            } else if (clInfo.getClazz().isArray()) {
                Class componentType = clInfo.getClazz().getComponentType();
                if (componentType.isArray())
                    throw new KsonParseException("nested arrays not supported", in);
                if (DEBUG_STACK) {
                    stack.push(new ParseStep("read array of type " + clInfo.getClazz().getComponentType().getName(), in));
                }
                List keyVals = readList(componentType, componentType);
                res = Array.newInstance(componentType, keyVals.size());
                for (int i = 0; i < keyVals.size(); i++) {
                    Array.set(res, i, keyVals.get(i));
                }
                if (DEBUG_STACK) {
                    stack.pop();
                }
            } else {
                res = clInfo.newInstance(true);
                if (res==null) {
                    throw new RuntimeException(clInfo.getClazz().getName()+" misses a default constructor. Instantiation failed.");
                }
                List keyVals = readObjectFields(clInfo);

                for (int i = 0; i < keyVals.size(); i += 2) {
                    String fi = (String) keyVals.get(i);
                    Object val = keyVals.get(i + 1);
                    Field field = clInfo.getFieldInfo(fi, null).getField();
                    if ( field.getType().isEnum() && val instanceof String) {
                        val = Enum.valueOf( (Class<Enum>)field.getType(), (String) val);
                    }
                    field.set(res, val);
                }
            }
            if (DEBUG_STACK) {
                stack.pop();
            }
            return res;
        } catch (Exception ex) {
            throw new KsonParseException("unexpected error, tried reading object", in, ex);
        }
    }

    /**
     * guess am object type from raw input stream.
     *
     * @return
     */
    protected String huntType() {
        skipWS();
        int ch;
        // just scan first sttribute and expect a string value which is taken as mapped class name
        while ((ch = in.readChar()) != ':' && ch != '}' && ch > 0) {
        }
        skipWS();
        return readString();
    }

    private String readString() {
        return readString(in.peekChar() == '\"' || in.peekChar() == '\'');
    }

    protected List readObjectFields(FSTClazzInfo targetClz) throws Exception {

        ArrayList result = new ArrayList();
        skipWS();

        if (DEBUG_STACK) {
            stack.push(new ParseStep("read object of type "+targetClz.getClazz().getName(),in));
        }
        while (in.peekChar() > 0 && in.peekChar() != '}' && in.peekChar() != ']') {

            if (in.peekChar() == ':' || in.peekChar() == ',') {
                in.readChar(); // skip
                skipWS();
            }
            String field = (String) readValue(String.class, null, null);
            if ( "_type".equals(field)) {
                skipWS();
                in.readChar(); // ':'
                skipWS();
                readString();
                skipWS();
                continue;
            }
            result.add(field);
            skipWS();
            if (in.peekChar() == ':' || in.peekChar() == ',') {
                in.readChar(); // skip
                skipWS();
            }
            FSTClazzInfo.FSTFieldInfo fieldInfo = targetClz.getFieldInfo(field, null);
            Class type = fieldInfo == null ? null : fieldInfo.getType();

            // special to parse argument lists using reflected class types
            if ( argTypesRessolver != null && type == Object[].class && fieldInfo.getField().getAnnotation(ArgTypes.class) != null) {
                Class argTypes[] = argTypesRessolver.getArgTypes(targetClz.getClazz(), result);
                if (argTypes != null ) {
                    skipWS();
                    int ch = in.readChar();
                    if (ch != '{' && ch != '[' ) {
                        throw new KsonParseException("expected { or [ ",in);
                    }
                    result.add(readList(argTypes, argTypes).toArray());
                    skipWS();
//                    consumed by readList
//                    ch = in.readChar();
//                    if (ch != '}' && ch != ']' ) {
//                        throw new KsonParseException("expected } or ] ",in);
//                    }
//                    skipWS();
                    continue;
                }
            }

            if (fieldInfo != null) {
                if (DEBUG_STACK) {
                    stack.push(new ParseStep("read field '"+fieldInfo.getField().getName()+"' of type "+type.getName(),in));
                }
                result.add(readValue(type, Kson.fumbleOutGenericKeyType(fieldInfo.getField()), Kson.fumbleOutGenericValueType(fieldInfo.getField())));
                if (DEBUG_STACK) {
                    stack.pop();
                }
            } else {
                System.out.println("No such field '" + field + "' on class " + targetClz.getClazz().getName());
            }
            skipWS();
        }
        in.readChar(); // consume }
        if (DEBUG_STACK) {
            stack.pop();
        }
        return result;
    }

    public KsonArgTypesResolver getArgTypesRessolver() {
        return argTypesRessolver;
    }

    public KsonDeserializer setArgTypesRessolver(KsonArgTypesResolver argTypesRessolver) {
        this.argTypesRessolver = argTypesRessolver;
        return this;
    }

    protected List readList(Class[] keyType, Class[] valueType) throws Exception {
        ArrayList result = new ArrayList();
        skipWS();
        boolean expectKey = true;
        int index = 0;
        while (in.peekChar() > 0 && in.peekChar() != '}' && in.peekChar() != ']') {
            skipWS();
            if (expectKey) {
                result.add(readValue(keyType[index], null, null));
                expectKey = !expectKey;
            } else {
                if (in.peekChar() == ':' || in.peekChar() == ',') {
                    in.readChar(); // skip
                    skipWS();
                }
                result.add(readValue(valueType[index], null, null));
                expectKey = !expectKey;
                // just ignore unnecessary stuff
                skipWS();
                if (in.peekChar() == ':' || in.peekChar() == ',') {
                    in.readChar(); // skip
                }
            }
            skipWS();
            index++;
        }
        in.readChar(); // consume }
        return result;
    }

    protected List readList(Class keyType, Class valueType) throws Exception {
        ArrayList result = new ArrayList();
        skipWS();
        boolean expectKey = true;
        while (in.peekChar() > 0 && in.peekChar() != '}' && in.peekChar() != ']') {
            skipWS();
            if (expectKey) {
                result.add(readValue(keyType, null, null));
                expectKey = !expectKey;
            } else {
                if (in.peekChar() == ':' || in.peekChar() == ',') {
                    in.readChar(); // skip
                    skipWS();
                }
                if (DEBUG_STACK) {
                    stack.push(new ParseStep("read value for key '" + result.get(result.size() - 1) + "'", in));
                }
                result.add(readValue(valueType, null, null));
                if (DEBUG_STACK) {
                    stack.pop();
                }
                expectKey = !expectKey;
                // just ignore unnecessary stuff
                skipWS();
                if (in.peekChar() == ':' || in.peekChar() == ',') {
                    in.readChar(); // skip
                }
            }
            skipWS();
        }
        in.readChar(); // consume }
        return result;
    }

    protected Object readValue(Class expected, Class genericKeyType, Class genericValueType) throws Exception {
        skipWS();
        int ch = in.peekChar();
        if (ch == '"' || ch == '\'' || isFromStringValue(expected)) {
            // string
            return mapper.coerceReading(expected, readString(ch == '"' || ch == '\''));
        } else if (Character.isLetter(ch) || ch == '{' || ch == '[') {
            if ( ch == '[' /*&& ! isContainer(expected) */&& (expected == null || expected == Object.class || expected.isInterface())) {
                in.readChar();
                if ( expected != null &&
                    ! Map.class.isAssignableFrom(expected) &&
                    Collection.class.isAssignableFrom(expected) &&
                    genericValueType == null ) {
                    // default vlaueType to keyType for nnon-maps
                    genericValueType = genericKeyType;
                }
                return readList(genericKeyType, genericValueType);
            } else {
                // object
                return readObject(expected, genericKeyType, genericValueType);
            }
        } else if (Character.isDigit(ch) || ch == '+' || ch == '-' || ch == '.') {
            Class type = expected;
            if (type == float.class || type == double.class) {
                // .. extra slow for float&double. fixme: optimize this
                String num = readNums();
                double val = Double.parseDouble(num);
                if (type == double.class) {
                    return val;
                } else if (type == float.class) {
                    return (float) val;
                } else if (type == String.class) {
                    return "" + val;
                } else {
                    throw new KsonParseException("cannot assign floating point to " + type.getName(), in);
                }
            } else {
                // num
                boolean neg = false;
                if (ch == '+') {
                    in.readChar();
                } else if (ch == '-') {
                    neg = true;
                    in.readChar();
                }
                long l = readLong() * (neg ? -1 : 1);
                if (in.peekChar()=='.') {
                    // untyped floating point. FIXME: very slow
                    final String dotValue = readString(false);
                    if ( type == float.class || type == Float.class ) {
                        return Float.parseFloat(l+dotValue);
                    }
                    return Double.parseDouble(l+dotValue);
                }
                if (type == boolean.class) {
                    return l != 0;
                } else if (type == byte.class || type == Byte.class) {
                    byte b = (byte) (((l + 256) & 0xff) - 256);
                    return b;
                } else if (type == char.class || type == Character.class) {
                    return (char) l;
                } else if (type == short.class || type == Short.class) {
                    return (short) l;
                } else if (type == int.class || type == Integer.class) {
                    return (int) l;
                } else if (type == long.class || type == Long.class) {
                    return l;
                } else if (type == String.class) {
                    return "" + l;
                } else {
                    return l;
                }
            }
        } else if (Character.isJavaIdentifierStart(ch)) { // last resort string
            return readString(false);
        }
        throw new KsonParseException("value expected", in);
    }

    private boolean isContainer(Class expected) {
        return expected != null && (expected.isArray() || Collection.class.isAssignableFrom(expected));
    }

    protected boolean isFromStringValue(Class type) {
        return type == String.class;
    }

    protected long readLong() {
        int read=0;
        long res = 0;
        long fak = 1;
        int ch = in.readChar();
        boolean empty = true;
        while (Character.isDigit(ch)) {
            read++;
            empty = false;
            res += (ch - '0') * fak;
            fak *= 10;
            ch = in.readChar();
        }
        in.back(1);
        long reverse = 0;
        while (read-- != 0) {
            reverse = reverse * 10 + (res % 10);
            res = res / 10;
        }
        if (empty)
            throw new KsonParseException("expected int type number",in);
        return reverse;
    }

    protected String readString(boolean quoted) {
        StringBuilder b = new StringBuilder(15);
        int end = quoted ? in.readChar() : ' '; // " or '
        int ch = in.readChar();
        while ((quoted && ch != end) ||
                (!quoted && ch > 32 && ch != '#' && ch != '}' && ch != ']' && ch != ':' && ch != ',' && !Character.isWhitespace(ch))) {
            if (ch == '\\') {
                ch = in.readChar();
                switch (ch) {
                    case '\\':
                        b.append(ch);
                        break;
                    case '"':
                        b.append('"');
                        break;
                    case '/':
                        b.append('/');
                        break;
                    case 'b':
                        b.append('\b');
                        break;
                    case 'f':
                        b.append('\f');
                        break;
                    case 'n':
                        b.append('\n');
                        break;
                    case 'r':
                        b.append('\r');
                        break;
                    case 't':
                        b.append('\t');
                        break;
                    case 'u':
                        b.append("\\u").append((char) in.readChar()).append((char) in.readChar()).append((char) in.readChar()).append((char) in.readChar());
                        break;
                    default:
                        throw new RuntimeException("unknown escape " + (char) ch + " in " + in.position());
                }
            } else {
                b.append((char) ch);
            }
            ch = in.readChar();
        }
        if (!quoted) {
            in.back(1);
        }
        return b.toString();
    }

    protected String readNums() {
        skipWS();
        int pos = in.position();
        int ch = in.readChar();
        while (Character.isDigit(ch) || ch == '.' || ch == 'E' || ch == 'e' || ch == '+' || ch == '-') {
            ch = in.readChar();
        }
        in.back(1);
        return in.getString(pos, in.position() - pos);
    }

    protected String readId() {
        skipWS();
        int pos = in.position();
        int ch = in.readChar();
        while (isIdPart(ch) && ch != ':' && ch != ',') {
            ch = in.readChar();
        }
        in.back(1);
        return in.getString(pos, in.position() - pos);
    }

    protected boolean isIdPart(int ch) {
        return Character.isLetterOrDigit(ch) || ch == '$' || ch == '#' || ch == '_' || ch == '.';
    }

    protected boolean isIdStart(int ch) {
        return Character.isLetter(ch) || ch == '$' || ch == '#' || ch == '_';
    }

}
