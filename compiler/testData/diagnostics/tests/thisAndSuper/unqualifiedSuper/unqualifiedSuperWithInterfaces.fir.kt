//  Interface  AnotherInterface
//          \  /
//           \/
//     DerivedInterface
//

interface Interface {
    fun foo() {}
    fun ambiguous() {}
    val ambiguousProp: Int
        get() = 222
}

interface AnotherInterface {
    fun ambiguous() {}
    val ambiguousProp: Int
        get() = 333
}

interface DerivedInterface: Interface, AnotherInterface {
    override fun foo() { super.foo() }
    override fun ambiguous() {
        super.ambiguous()
    }
    override val ambiguousProp: Int
        get() = super.ambiguousProp
}

