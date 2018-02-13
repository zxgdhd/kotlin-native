import Foundation
import Values

// -------- Tests --------

func testVals() throws {
    print("Values from Swift")
    let dbl = Values.dbl()
    let flt = Values.flt()
    let int = Values.int()
    let long = Values.long()
    
    print(dbl)
    print(flt)
    print(int)
    print(long)
    
    try assertEquals(actual: dbl, expected: 3.14 as Double, "Double value isn't equal.")
    try assertEquals(actual: flt, expected: 2.73 as Float, "Float value isn't equal.")
    try assertEquals(actual: int, expected: 42)
    try assertEquals(actual: long, expected: 1984)
}

func testVars() throws {
    print("Variables from Swift")
    var intVar = Values.intVar()
    var strVar = Values.str()
    var strAsId = Values.strAsAny()
    
    print(intVar)
    print(strVar)
    print(strAsId)
    
    try assertEquals(actual: intVar, expected: 451)
    try assertEquals(actual: strVar, expected: "Kotlin String")
    try assertEquals(actual: strAsId as! String, expected: "Kotlin String as Any")
    
    strAsId = "Swift str"
    Values.setStrAsAny(strAsId)
    print(Values.strAsAny())
    try assertEquals(actual: Values.strAsAny() as! String, expected: strAsId as! String)
    
    // property with custom getter/setter backed by the Kotlin's var
    var intProp : Int32 {
        get {
            return Values.intVar() * 2
        }
        set(value) {
            Values.setIntVar(123 + value)
        }
    }
    intProp += 10
    print(intProp)
    print(Values.intVar())
    try assertEquals(actual: Values.intVar() * 2, expected: intProp, "Property backed by var")
}

func testDoubles() throws {
    print("Doubles in Swift")
    let minDouble = Values.minDoubleVal()
    let maxDouble = Values.maxDoubleVal()

    print(minDouble)
    print(maxDouble)
    print(Values.nanDoubleVal())
    print(Values.nanFloatVal())
    print(Values.infDoubleVal())
    print(Values.infFloatVal())

//    try assertEquals(actual: minDouble, expected: Double.leastNonzeroMagnitude as NSNumber, "Min double")
//    try assertEquals(actual: maxDouble, expected: Double.greatestFiniteMagnitude as NSNumber, "Max double")
    try assertTrue(Values.nanDoubleVal().isNaN, "NaN double")
    try assertTrue(Values.nanFloatVal().isNaN, "NaN float")
    try assertEquals(actual: Values.infDoubleVal(), expected: Double.infinity, "Inf double")
    try assertEquals(actual: Values.infFloatVal(), expected: -Float.infinity, "-Inf float")
}

func testLists() throws {
    let numbersList = Values.numbersList()
    let gold = [1, 2.3 as Float, 13.13]
    for i in numbersList {
        print(i)
    }
//    try assertEquals(actual: gold, expected: numbersList as [NSNumber], "Numbers list")

    let anyList = Values.anyList()
    for i in anyList {
        print(i)
    }
//    try assertEquals(actual: gold, expected: anyList, "Numbers list")
}

func testLazyVal() throws {
    let lazyVal = Values.lazyVal()
    print(lazyVal)
    try assertEquals(actual: lazyVal, expected: "Lazily initialized string", "lazy value")
}

let goldenArray = ["Delegated", "global", "array", "property"]

func testDelegatedProp() throws {
    let delegatedGlobalArray = Values.delegatedGlobalArray()
    guard Int(delegatedGlobalArray.size) == goldenArray.count else {
        throw TestError.assertFailed("Size differs")
    }
    for i in 0..<delegatedGlobalArray.size {
        print(delegatedGlobalArray.get(index: i)!)
    }
}

func testGetterDelegate() throws {
    let delegatedList = Values.delegatedList()
    guard delegatedList.count == goldenArray.count else {
        throw TestError.assertFailed("Size differs")
    }
    for val in delegatedList {
        print(val)
    }
}

func testNulls() throws {
    let nilVal : Any? = Values.nullVal()
    try assertTrue(nilVal == nil, "Null value")

    Values.setNullVar(nil)
    var nilVar : Any? = Values.nullVar()
    try assertTrue(nilVar == nil, "Null variable")
}

func testAnyVar() throws {
    var anyVar : Any = Values.anyValue()
    print(anyVar)
    if let str = anyVar as? String {
        print(str)
        try assertEquals(actual: str, expected: "Str")
    } else {
        throw TestError.assertFailed("Incorrect type passed from Any")
    }
}

func testFunctions() throws {
    let _: Any? = Values.emptyFun()

    let str = Values.strFun()
    try assertEquals(actual: str, expected: "fooStr")

    try assertEquals(actual: Values.argsFun(i: 10, l: 20, d: 3.5, s: "res") as! String,
            expected: "res10203.5")
}

func testFuncType() throws {
    let s = "str"
    let fFunc: () -> String = { return s }
    try assertEquals(actual: Values.funArgument(foo: fFunc), expected: s, "Using function type arguments failed")
}

func testGenericsFoo() throws {
    let fun = { (i: Int) -> String in return "S \(i)" }
    // wrap lambda to workaround issue with type conversion inability:
    // (Int) -> String can't be cast to (Any?) -> Any?
    let wrapper = { (t: Any?) -> Any? in return fun(t as! Int) }
    let res = Values.genericFoo(t: 42, foo: wrapper)
    try assertEquals(actual: res as! String, expected: "S 42")
}

func testVararg() throws {
    let ktArray = ValuesStdlibArray(size: 3, init: { (_) -> Int in return 42 })
    let arr: [Int] = Values.varargToList(args: ktArray) as! [Int]
    try assertEquals(actual: arr, expected: [42, 42, 42])
}

func testStrExtFun() throws {
    try assertEquals(actual: Values.subExt("String", i: 2), expected: "r")
    try assertEquals(actual: Values.subExt("A", i: 2), expected: "nothing")
}

func testAnyToString() throws {
    try assertEquals(actual: Values.toString(nil), expected: "null")
    try assertEquals(actual: Values.toString(42), expected: "42")
}

func testAnyPrint() throws {
    print("BEGIN")
    Values.print(nil)
    Values.print("Print")
    Values.print(123456789)
    Values.print(3.14)
    Values.print([3, 2, 1])
    print("END")
}

func testLambda() throws {
    try assertEquals(actual: Values.sumLambda()(3, 4), expected: 7)
    // FIXME: fails because Floats passed as NSNumbers while lambda defines parameters as Ints
    // try assertEquals(actual: Values.sumLambda()(3.14, 2.71), expected: 5.85)
}

// -------- Tests for classes and interfaces -------
class ValIEmptyExt : ValuesI {
    func iFun() -> String {
        return "ValIEmptyExt::iFun"
    }
}

class ValIExt : ValuesI {
    func iFun() -> String {
        return "ValIExt::iFun"
    }
}

func testInterfaceExtension() throws {
    try assertEquals(actual: ValIEmptyExt().iFun(), expected: "ValIEmptyExt::iFun")
    try assertEquals(actual: ValIExt().iFun(), expected: "ValIExt::iFun")
}

func testClassInstances() throws {
    try assertEquals(actual: ValuesOpenClassI().iFun(), expected: "OpenClassI::iFun")
    try assertEquals(actual: ValuesDefaultInterfaceExt().iFun(), expected: "I::iFun")
    try assertEquals(actual: ValuesFinalClassExtOpen().iFun(), expected: "FinalClassExtOpen::iFun")
    try assertEquals(actual: ValuesMultiExtClass().iFun(), expected: "PI::iFun")
    try assertEquals(actual: ValuesMultiExtClass().piFun() as! Int, expected: 42)
    try assertEquals(actual: ValuesConstrClass(i: 1, s: "str", a: "Any").iFun(), expected: "OpenClassI::iFun")
    try assertEquals(actual: ValuesExtConstrClass(i: 123).iFun(), expected: "ExtConstrClass::iFun::123-String-AnyS")
}

func testEnum() throws {
    try assertEquals(actual: Values.passEnum().enumValue, expected: 42)
    try assertEquals(actual: Values.passEnum().name, expected: "ANSWER")
    Values.receiveEnum(e: 1)
}

func testDataClass() throws {
    let f = "1"
    let s = "2"
    let t = "3"

    let tripleVal = ValuesTripleVals(first: f, second: s, third: t)
    try assertEquals(actual: tripleVal.first as! String, expected: f, "Data class' value")
    try assertEquals(actual: tripleVal.component2() as! String, expected: s, "Data class' component")
    print(tripleVal)
    try assertEquals(actual: String(describing: tripleVal), expected: "TripleVals(first=\(f), second=\(s), third=\(t))")

    let tripleVar = ValuesTripleVars(first: f, second: s, third: t)
    try assertEquals(actual: tripleVar.first as! String, expected: f, "Data class' value")
    try assertEquals(actual: tripleVar.component2() as! String, expected: s, "Data class' component")
    print(tripleVar)
    try assertEquals(actual: String(describing: tripleVar), expected: "[\(f), \(s), \(t)]")

    tripleVar.first = t
    tripleVar.second = f
    tripleVar.third = s
    try assertEquals(actual: tripleVar.component2() as! String, expected: f, "Data class' component")
    try assertEquals(actual: String(describing: tripleVar), expected: "[\(t), \(f), \(s)]")
}

func testCompanionObj() throws {
    // FIXME: unable to get companion's str property
//    try assertEquals(actual: ValuesWithCompanionAndObjectCompanion.str, expected: "String")
    try assertEquals(actual: Values.getCompanionObject().str, expected: "String")

    let namedFromCompanion = Values.getCompanionObject().named
    let named = Values.getNamedObject()
    try assertTrue(named === namedFromCompanion, "Should be the same Named object")

    try assertEquals(actual: Values.getNamedObjectInterface().iFun(), expected: named.iFun(), "Named object's method")
}

func testGenericMapUsage() throws {
    //let map = Values.createMutableMap()
    //map.put(key: 1, value: "One")
    //map.put(key: 10, value: "Ten")
    //map.put(key: 11, value: "Eleven")
    //map.put(key: "10", value: "Ten")
    //let gen = ValuesGenericExtensionClass(holder: map)
    //let value : String? = gen.getFirstValue() as? String
    //try assertEquals(actual: value!, expected: "One", "First value of the map")
}

func testTypedMapUsage() throws {
    /*let map = Values.createTypedMutableMap()
    map.put(key: 1, value: "One")
    map.put(key: 1.0 as Float, value: "Float")
    map.put(key: 11, value: "Eleven")
    map.put(key: "10", value: "Ten as string")
    let gen = ValuesGenericExtensionClass(holder: map)
    let value : String? = gen.getFirstValue() as? String
    try assertEquals(actual: value!, expected: "One", "First value of the map")
*/
}

func zeroTo(_ n: Int32) -> ValuesStdlibArray { return ValuesStdlibArray(size: n) { $0 } }

func testList() throws {
    let elements = zeroTo(5)
    elements.set(index: 1, value: nil)
    let list = Values.list(elements: elements) as! NSArray
    try assertEquals(actual: list.object(at: 2) as! NSNumber, expected: NSNumber(value: 2))
    try assertEquals(actual: list.object(at: 1) as! NSNull, expected: NSNull())
    try assertEquals(actual: list.count, expected: 5)
}

func testMutableList() throws {
    let kotlinList = Values.emptyMutableList() as! NSMutableArray
    let nsList = NSMutableArray()

    func apply<T : Equatable>(op: (NSMutableArray)->T) throws {
        let actual = op(kotlinList)
        let expected = op(nsList)
        try assertEquals(actual: actual, expected: expected)
        try assertEquals(actual: kotlinList, expected: nsList)
        try assertEquals(actual: kotlinList.hash, expected: nsList.hash)
    }

    func applyVoid(op: (NSMutableArray)->Void) throws {
        op(kotlinList)
        op(nsList)
        try assertEquals(actual: kotlinList, expected: nsList)
        try assertEquals(actual: kotlinList.hash, expected: nsList.hash)
    }

    try apply { $0.count }
    try applyVoid { $0.insert(0, at: 0) }
    try applyVoid { $0.insert(1, at: 0) }
    try applyVoid { $0.insert(2, at: 1) }
    try applyVoid { $0.removeObject(at: 0) }
    try applyVoid { $0.add("foo") }
    try applyVoid { $0.removeLastObject() }
    try applyVoid { $0.replaceObject(at: 0, with: "bar") }
    let NULL: Any? = nil
    try applyVoid { $0.add(NULL as Any) }
    try applyVoid { $0.insert(NULL as Any, at: 2) }
    try applyVoid { $0.replaceObject(at: 1, with: NULL as Any) }
    try apply { $0.count }
}

func testMutableSet() throws {
    let kotlinSet = Values.emptyMutableSet() as! NSMutableSet
    let nsSet = NSMutableSet()

    func apply<T : Equatable>(op: (NSMutableSet)->T) throws {
        let actual = op(kotlinSet)
        let expected = op(nsSet)
        try assertEquals(actual: actual, expected: expected)
        try assertEquals(actual: kotlinSet, expected: nsSet)
        try assertEquals(actual: kotlinSet.hash, expected: nsSet.hash)
    }

    func applyVoid(op: (NSMutableSet)->Void) throws {
        op(kotlinSet)
        op(nsSet)
        try assertEquals(actual: kotlinSet, expected: nsSet)
        try assertEquals(actual: kotlinSet.hash, expected: nsSet.hash)
    }

    try apply { $0.count }
    try applyVoid { $0.add("foo") }
    try applyVoid { $0.add("bar") }
    try applyVoid { $0.remove("baz") }
    try applyVoid { $0.add("baz") }
    try applyVoid { $0.add(ValuesTripleVals(first: 1, second: 2, third: 3)) }
    try apply { $0.member(ValuesTripleVals(first: 1, second: 2, third: 3)) as! ValuesTripleVals }
    try apply { $0.member(42) == nil }
    try applyVoid { $0.remove(ValuesTripleVals(first: 1, second: 2, third: 3)) }

    let NULL0: Any? = nil
    let NULL = NULL0 as Any

    try applyVoid { $0.add(NULL) }
    try apply { $0.member(NULL) == nil }
    try apply { $0.member(NULL) as! NSObject }
    try applyVoid { $0.remove(NULL) }
    try apply { $0.member(NULL) == nil }

    try apply { NSSet(array: $0.objectEnumerator().remainingObjects()) }

    try apply { $0.count }
}

func testMutableMap() throws {
    // TODO: test KotlinMutableSet/Dictionary constructors
    let kotlinMap = Values.emptyMutableMap() as! NSMutableDictionary
    let nsMap = NSMutableDictionary()

    func apply<T : Equatable>(op: (NSMutableDictionary)->T) throws {
        let actual = op(kotlinMap)
        let expected = op(nsMap)
        try assertEquals(actual: actual, expected: expected)
        try assertEquals(actual: kotlinMap, expected: nsMap)
        try assertEquals(actual: kotlinMap.hash, expected: nsMap.hash)
    }

    func applyVoid(op: (NSMutableDictionary) throws -> Void) throws {
        try op(kotlinMap)
        try op(nsMap)
        try assertEquals(actual: kotlinMap, expected: nsMap)
        try assertEquals(actual: kotlinMap.hash, expected: nsMap.hash)
    }

    try apply { $0.count }
    try apply { $0.object(forKey: 42) == nil }
    try applyVoid { $0.setObject(42, forKey: 42 as NSNumber) }
    try applyVoid { $0.setObject(17, forKey: "foo" as NSString) }
    let triple = ValuesTripleVals(first: 3, second: 2, third: 1)
    try applyVoid { $0.setObject("bar", forKey: triple) }
    try applyVoid { $0.removeObject(forKey: 42) }
    try apply { $0.count }
    try apply { $0.object(forKey: 42) == nil }
    try apply { $0.object(forKey: "foo") as! NSObject  }
    try apply { $0.object(forKey: triple) as! NSObject }

    try apply { NSSet(array: $0.keyEnumerator().remainingObjects()) }

    let NULL0: Any? = nil
    let NULL = NULL0 as Any

    try apply { $0.object(forKey: NULL) == nil }


    try applyVoid { $0.setObject(42, forKey: NULL as! NSCopying) }
    try applyVoid { $0.setObject(NULL, forKey: "baz" as NSString) }
    try apply { $0.object(forKey: NULL) as! NSObject }
    try apply { $0.object(forKey: "baz") as! NSObject }

    try apply { NSSet(array: $0.keyEnumerator().remainingObjects()) }

    try applyVoid { $0.removeObject(forKey: NULL) }
    try applyVoid { $0.removeObject(forKey: "baz") }

    try applyVoid { $0.removeAllObjects() }

    let myKey = MyKey()
    try applyVoid { $0.setObject(myKey, forKey: myKey) }
    try applyVoid {
        let key = $0.allKeys[0] as! MyKey
        let value = $0.allValues[0] as! MyKey
        try assertFalse(key === myKey)
        try assertTrue(value === myKey)
    }

}

@objc class MyKey : NSObject, NSCopying {
    override var hash: Int {
        return 42
    }

    override func isEqual(_ object: Any?) -> Bool {
        return object is MyKey
    }

    func copy(with: NSZone? = nil) -> Any {
        return MyKey()
    }

}



extension NSEnumerator {
    func remainingObjects() -> [Any?] {
        var result = [Any?]()
        while (true) {
            if let next = self.nextObject() {
                result.append(next as AnyObject as Any?) 
            } else {
                break
            }
        }
        return result
    }
}

func testSet() throws {
    let elements = ValuesStdlibArray(size: 2) { index in nil }
    elements.set(index: 0, value: nil)
    elements.set(index: 1, value: 42)
    let set = Values.set(elements: elements) as! NSSet
    try assertEquals(actual: set.count, expected: 2)
    try assertEquals(actual: set.member(NSNull()) as! NSNull, expected: NSNull())
    try assertEquals(actual: set.member(42) as! NSNumber, expected: NSNumber(value: 42 as Int32))
    try assertTrue(set.member(17) == nil)
    try assertFalse(set.member(42) as AnyObject === NSNumber(value: 42 as Int32))
    try assertTrue(set.contains(42))
    try assertTrue(set.contains(nil as Any?))
    try assertFalse(set.contains(17))

    try assertEquals(actual: NSSet(array: set.objectEnumerator().remainingObjects()), expected: NSSet(array: [nil, 42] as [AnyObject]))
}

func testMap() throws {
    let elements = ValuesStdlibArray(size: 6) { index in nil }
    elements.set(index: 0, value: nil)
    elements.set(index: 1, value: 42)
    elements.set(index: 2, value: "foo")
    elements.set(index: 3, value: "bar")
    elements.set(index: 4, value: 42)
    elements.set(index: 5, value: nil)

    let map = Values.map(keysAndValues: elements) as! NSDictionary
    try assertEquals(actual: map.count, expected: 3)

    try assertEquals(actual: map.object(forKey: NSNull()) as! NSNumber, expected: NSNumber(value: 42))
    try assertEquals(actual: map.object(forKey: "foo") as! String, expected: "bar")
    try assertEquals(actual: map.object(forKey: 42) as! NSNull, expected: NSNull())
    try assertTrue(map.object(forKey: "bar") == nil)

    try assertEquals(actual: NSSet(array: map.keyEnumerator().remainingObjects()), expected: NSSet(array: [nil, 42, "foo"] as [AnyObject]))
}

// -------- Execution of the test --------

private func withAutorelease( _ method: @escaping () throws -> Void) -> () throws -> Void {
    return { () throws -> Void in
        try autoreleasepool { try method() }
    }
}

class ValuesTests : TestProvider {
    var tests: [TestCase] = []

    init() {
        providers.append(self)
        tests.append(TestCase(name: "TestList", method: withAutorelease(testList)))
        tests.append(TestCase(name: "TestMutableList", method: withAutorelease(testMutableList)))
        tests.append(TestCase(name: "TestSet", method: withAutorelease(testSet)))
        tests.append(TestCase(name: "TestMutableSet", method: withAutorelease(testMutableSet)))
        tests.append(TestCase(name: "TestMap", method: withAutorelease(testMap)))
        tests.append(TestCase(name: "TestMutableMap", method: withAutorelease(testMutableMap)))
        tests.append(TestCase(name: "TestValues", method: withAutorelease(testVals)))
        tests.append(TestCase(name: "TestVars", method: withAutorelease(testVars)))
        tests.append(TestCase(name: "TestDoubles", method: withAutorelease(testDoubles)))
        tests.append(TestCase(name: "TestLists", method: withAutorelease(testLists)))
        tests.append(TestCase(name: "TestLazyValues", method: withAutorelease(testLazyVal)))
        tests.append(TestCase(name: "TestDelegatedProperties", method: withAutorelease(testDelegatedProp)))
        tests.append(TestCase(name: "TestGetterDelegate", method: withAutorelease(testGetterDelegate)))
        tests.append(TestCase(name: "TestNulls", method: withAutorelease(testNulls)))
        tests.append(TestCase(name: "TestAnyVar", method: withAutorelease(testAnyVar)))
        tests.append(TestCase(name: "TestFunctions", method: withAutorelease(testFunctions)))
        tests.append(TestCase(name: "TestFuncType", method: withAutorelease(testFuncType)))
        tests.append(TestCase(name: "TestGenericsFoo", method: withAutorelease(testGenericsFoo)))
        tests.append(TestCase(name: "TestVararg", method: withAutorelease(testVararg)))
        tests.append(TestCase(name: "TestStringExtenstion", method: withAutorelease(testStrExtFun)))
        tests.append(TestCase(name: "TestAnyToString", method: withAutorelease(testAnyToString)))
        tests.append(TestCase(name: "TestAnyPrint", method: withAutorelease(testAnyPrint)))
        tests.append(TestCase(name: "TestLambda", method: withAutorelease(testLambda)))
        tests.append(TestCase(name: "TestInterfaceExtension", method: withAutorelease(testInterfaceExtension)))
        tests.append(TestCase(name: "TestClassInstances", method: withAutorelease(testClassInstances)))
        tests.append(TestCase(name: "TestEnum", method: withAutorelease(testEnum)))
        tests.append(TestCase(name: "TestDataClass", method: withAutorelease(testDataClass)))
        tests.append(TestCase(name: "TestCompanionObj", method: withAutorelease(testCompanionObj)))
        tests.append(TestCase(name: "TestGenericMapUsage", method: withAutorelease(testGenericMapUsage)))
        tests.append(TestCase(name: "TestTypedMapUsage", method: withAutorelease(testTypedMapUsage)))
    }
}
