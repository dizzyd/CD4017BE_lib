package cd4017be.lib.jvm_utils;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants.NBT;

import static cd4017be.lib.jvm_utils.ClassAssembler.*;

/**
 * Class file generator function for loading classes from NBT data.
 * @author CD4017BE
 */
public class NBT2Class implements Function<String, byte[]> {

	public final NBTTagCompound nbt;
	private final SecurityChecker checker;
	private final Class<?> type;
	private final List<Class<?>> interfaces;
	private short fieldAcc = 0x1002;
	private boolean defConstr = false;

	/**
	 * @param nbt NBT data in format:<br>
	 * {@code "cpt": list(byte[])}  non default constant pool table entries<br>
	 * {@code "methods": list(byte[])}  method entries<br>
	 * {@code "fields": int[]}  field entries where {@code int = 0xNameType}
	 * @param type super class
	 * @param interfaces implemented interfaces
	 */
	public NBT2Class(NBTTagCompound nbt, SecurityChecker checker, Class<?> type, Class<?>... interfaces) {
		this.nbt = nbt;
		this.checker = checker;
		this.type = type;
		this.interfaces = Arrays.asList(interfaces);
	}

	/**
	 * specifies that a default constructor should be added to the methods list
	 * @return this
	 */
	public NBT2Class addConstructor() {
		defConstr = true;
		return this;
	}

	/**
	 * specifies that the fields should have public access instead of private
	 * @return this
	 */
	public NBT2Class publicFields() {
		fieldAcc = 0x1001;
		return this;
	}

	/**
	 * called by {@link ClassAssembler} when class is loaded
	 * @param name the (generic) name of the class to generate
	 * @return the generated class file data
	 */
	@Override
	public byte[] apply(String name) {
		//load constant pool table
		ConstantPool cpt = new ConstantPool(name, type);
		for (NBTBase tag : nbt.getTagList("cpt", NBT.TAG_BYTE_ARRAY))
			cpt.add(((NBTTagByteArray)tag).getByteArray());
		//load methods
		ArrayList<byte[]> fields = new ArrayList<>(), methods = new ArrayList<>();
		if (defConstr) methods.add(DEFAULT_CONSTR);
		for (NBTBase tag : nbt.getTagList("methods", NBT.TAG_BYTE_ARRAY))
			methods.add(((NBTTagByteArray)tag).getByteArray());
		//load fields
		for (int ni : nbt.getIntArray("fields")) {
			ByteBuffer b = ByteBuffer.allocate(8);
			b.putShort(fieldAcc);
			b.putInt(2, ni);
			fields.add(b.array().clone());
		}
		byte[] data = genClass(interfaces, cpt, fields, methods, null);
		checker.verify(data);
		return data;
	}

	/**
	 * @return a 128-bit unique identification hash for this class
	 */
	public UUID getHash() {
		if (nbt.hasKey("uidM", NBT.TAG_LONG) && nbt.hasKey("uidL", NBT.TAG_LONG))
			return new UUID(nbt.getLong("uidM"), nbt.getLong("uidL"));
		NBTTagList cpt = nbt.getTagList("cpt", NBT.TAG_BYTE_ARRAY),
				methods = nbt.getTagList("methods", NBT.TAG_BYTE_ARRAY);
		byte[][] data = new byte[cpt.tagCount() + methods.tagCount()][];
		int i = 0, n = 0;
		for (NBTBase tag : cpt) {
			byte[] arr = ((NBTTagByteArray)tag).getByteArray();
			data[i++] = arr;
			n += arr.length;
		}
		for (NBTBase tag : methods) {
			byte[] arr = ((NBTTagByteArray)tag).getByteArray();
			data[i++] = arr;
			n += arr.length;
		}
		int[] fields = nbt.getIntArray("fields");
		ByteBuffer buf = ByteBuffer.allocate(n + fields.length * 4 + 1);
		for (byte[] arr : data) buf.put(arr);
		for (int f : fields) buf.putInt(f);
		buf.put((byte)(fieldAcc | (defConstr ? 4 : 0)));
		
		StringBuilder tag = new StringBuilder(type.getSimpleName());
		for (Class<?> c : interfaces) tag.append(',').append(c.getSimpleName());
		UUID uid = ClassUtils.hash(tag.toString(), buf.array());
		nbt.setLong("uidM", uid.getMostSignificantBits());
		nbt.setLong("uidL", uid.getLeastSignificantBits());
		return uid;
	}

	/**
	 * packs a class draft as NBTTagCompound
	 * @param cpt constant pool table
	 * @param fields pairs of field name and type indices: {@code int = 0xNameType}
	 * @param methods method entries
	 * @return packed data
	 */
	public static NBTTagCompound writeNBT(ConstantPool cpt, int[] fields, List<byte[]> methods) {
		NBTTagCompound nbt = new NBTTagCompound();
		NBTTagList list = new NBTTagList();
		for (byte[] arr : methods)
			list.appendTag(new NBTTagByteArray(arr));
		nbt.setTag("methods", list);
		
		nbt.setIntArray("fields", fields);
		list = new NBTTagList();
		for (int i = ConstantPool.PREINIT_LEN, l = cpt.getCount(); i < l; i++)
			list.appendTag(new NBTTagByteArray(cpt.get(i)));
		nbt.setTag("cpt", list);
		return nbt;
	}

	/**
	 * resolves named fields into name and type
	 * @param fields list of field descriptors
	 * @param cpt constant pool table
	 * @return pair of name and type indices:  {@code int = 0xNameType}
	 */
	public static int[] genFields(List<String> fields, ConstantPool cpt) {
		int[] indices = new int[fields.size()];
		for (int i = 0, l = indices.length; i < l; i++) {
			String descr = fields.get(i);
			int p = descr.indexOf(' ');
			if (p < 0) throw new IllegalArgumentException();
			indices[i] = cpt.putUtf8(descr.substring(p + 1)) << 16 & 0xffff0000
				| cpt.putUtf8(descr.substring(0, p).replace('.', '/')) & 0xffff;
		}
		return indices;
	}

	/**
	 * references a local field
	 * @param field pair of name and type indices:  {@code int = 0xNameType}
	 * @param cpt constant pool table
	 * @return field reference index
	 */
	public static short refField(int field, ConstantPool cpt) {
		return cpt.putFieldMethod(ConstantPool.THIS_CLASS, cpt.putNameType((short)(field >> 16), (short)field), ConstantPool.FIELD);
	}

}